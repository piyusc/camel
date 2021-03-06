/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.processor.loadbalancer;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Traceable;
import org.apache.camel.util.AsyncProcessorConverterHelper;

public class CircuitBreakerLoadBalancer extends LoadBalancerSupport implements Traceable, CamelContextAware {
    private static final int STATE_CLOSED = 0;
    private static final int STATE_HALF_OPEN = 1;
    private static final int STATE_OPEN = 2;

    private final List<Class<?>> exceptions;
    private CamelContext camelContext;
    private int threshold;
    private long halfOpenAfter;
    private long lastFailure;
    private AtomicInteger failures = new AtomicInteger();
    private AtomicInteger state = new AtomicInteger(STATE_CLOSED);

    public CircuitBreakerLoadBalancer(List<Class<?>> exceptions) {
        this.exceptions = exceptions;
    }

    public CircuitBreakerLoadBalancer() {
        this.exceptions = null;
    }

    public void setHalfOpenAfter(long halfOpenAfter) {
        this.halfOpenAfter = halfOpenAfter;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    public List<Class<?>> getExceptions() {
        return exceptions;
    }

    protected boolean hasFailed(Exchange exchange) {
        boolean answer = false;

        if (exchange.getException() != null) {
            if (exceptions == null || exceptions.isEmpty()) {
                answer = true;
            } else {
                for (Class<?> exception : exceptions) {
                    if (exchange.getException(exception) != null) {
                        answer = true;
                        break;
                    }
                }
            }
        }
        return answer;
    }

    @Override
    public boolean isRunAllowed() {
        boolean forceShutdown = camelContext.getShutdownStrategy().forceShutdown(this);
        if (forceShutdown) {
            log.trace("Run not allowed as ShutdownStrategy is forcing shutting down");
        }
        return !forceShutdown && super.isRunAllowed();
    }

    public boolean process(final Exchange exchange, final AsyncCallback callback) {

        // can we still run
        if (!isRunAllowed()) {
            log.trace("Run not allowed, will reject executing exchange: {}", exchange);
            if (exchange.getException() == null) {
                exchange.setException(new RejectedExecutionException("Run is not allowed"));
            }
            callback.done(true);
            return true;
        }

        return calculateState(exchange, callback);
    }

    private boolean calculateState(final Exchange exchange, final AsyncCallback callback) {
        boolean output = false;
        if (state.get() == STATE_HALF_OPEN) {
            if (failures.get() == 0) {
                output = closeCircuit(exchange, callback);
            } else {
                output = openCircuit(exchange, callback);
            }
        } else if (state.get() == STATE_OPEN) {
            if (failures.get() >= threshold && System.currentTimeMillis() - lastFailure < halfOpenAfter) {
                output = openCircuit(exchange, callback);
            } else {
                output = halfOpenCircuit(exchange, callback);
            }
        } else if (state.get() == STATE_CLOSED) {
            if (failures.get() >= threshold && System.currentTimeMillis() - lastFailure < halfOpenAfter) {
                output = openCircuit(exchange, callback);
            } else if (failures.get() >= threshold && System.currentTimeMillis() - lastFailure >= halfOpenAfter) {
                output = halfOpenCircuit(exchange, callback);
            } else {
                output = closeCircuit(exchange, callback);
            }
        } else {
            throw new IllegalStateException("Unrecognised circuitBreaker state " + state.get());
        }
        return output;
    }

    private boolean openCircuit(final Exchange exchange, final AsyncCallback callback) {
        boolean output = rejectExchange(exchange, callback);
        state.set(STATE_OPEN);
        logState();
        return output;
    }

    private boolean halfOpenCircuit(final Exchange exchange, final AsyncCallback callback) {
        boolean output = executeProcessor(exchange, callback);
        state.set(STATE_HALF_OPEN);
        logState();
        return output;
    }

    private boolean closeCircuit(final Exchange exchange, final AsyncCallback callback) {
        boolean output = executeProcessor(exchange, callback);
        state.set(STATE_CLOSED);
        logState();
        return output;
    }

    private void logState() {
        log.debug("State {}, failures {}, closed since {}", new Object[]{state.get(), failures.get(), System.currentTimeMillis() - lastFailure});
    }

    private boolean executeProcessor(final Exchange exchange, final AsyncCallback callback) {
        Processor processor = getProcessors().get(0);
        if (processor == null) {
            throw new IllegalStateException("No processors could be chosen to process CircuitBreaker");
        }

        AsyncProcessor albp = AsyncProcessorConverterHelper.convert(processor);
        // Added a callback for processing the exchange in the callback
        boolean sync = albp.process(exchange, new CircuitBreakerCallback(exchange, callback));

        // We need to check the exception here as albp is use sync call
        if (sync) {
            boolean failed = hasFailed(exchange);
            if (!failed) {
                failures.set(0);
            } else {
                failures.incrementAndGet();
                lastFailure = System.currentTimeMillis();
            }
        } else {
            // CircuitBreakerCallback can take care of failure check of the
            // exchange
            log.trace("Processing exchangeId: {} is continued being processed asynchronously", exchange.getExchangeId());
            return false;
        }

        log.trace("Processing exchangeId: {} is continued being processed synchronously", exchange.getExchangeId());
        callback.done(true);
        return true;
    }

    private boolean rejectExchange(final Exchange exchange, final AsyncCallback callback) {
        exchange.setException(new RejectedExecutionException("CircuitBreaker Open: failures: " + failures + ", lastFailure: " + lastFailure));
        callback.done(true);
        return true;
    }

    public String toString() {
        return "CircuitBreakerLoadBalancer[" + getProcessors() + "]";
    }

    public String getTraceLabel() {
        return "circuitbreaker";
    }

    class CircuitBreakerCallback implements AsyncCallback {
        private final AsyncCallback callback;
        private final Exchange exchange;

        CircuitBreakerCallback(Exchange exchange, AsyncCallback callback) {
            this.callback = callback;
            this.exchange = exchange;
        }

        @Override
        public void done(boolean doneSync) {
            if (!doneSync) {
                boolean failed = hasFailed(exchange);
                if (!failed) {
                    failures.set(0);
                } else {
                    failures.incrementAndGet();
                    lastFailure = System.currentTimeMillis();
                }
            }
            callback.done(doneSync);
        }

    }
}
