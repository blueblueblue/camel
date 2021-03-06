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
package org.apache.camel.processor.interceptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.Channel;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Service;
import org.apache.camel.model.ModelChannel;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.processor.InterceptorToAsyncProcessorBridge;
import org.apache.camel.processor.RouteContextProcessor;
import org.apache.camel.processor.WrapProcessor;
import org.apache.camel.spi.InterceptStrategy;
import org.apache.camel.spi.LifecycleStrategy;
import org.apache.camel.spi.RouteContext;
import org.apache.camel.support.ServiceSupport;
import org.apache.camel.util.AsyncProcessorHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.OrderedComparator;
import org.apache.camel.util.ServiceHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DefaultChannel is the default {@link Channel}.
 * <p/>
 * The current implementation is just a composite containing the interceptors and error handler
 * that beforehand was added to the route graph directly.
 * <br/>
 * With this {@link Channel} we can in the future implement better strategies for routing the
 * {@link Exchange} in the route graph, as we have a {@link Channel} between each and every node
 * in the graph.
 *
 * @version 
 */
public class DefaultChannel extends ServiceSupport implements ModelChannel {

    private static final transient Logger LOG = LoggerFactory.getLogger(DefaultChannel.class);

    private final List<InterceptStrategy> interceptors = new ArrayList<InterceptStrategy>();
    private Processor errorHandler;
    // the next processor (non wrapped)
    private Processor nextProcessor;
    // the real output to invoke that has been wrapped
    private Processor output;
    private ProcessorDefinition<?> definition;
    private ProcessorDefinition<?> childDefinition;
    private CamelContext camelContext;
    private RouteContext routeContext;
    private RouteContextProcessor routeContextProcessor;

    public List<Processor> next() {
        List<Processor> answer = new ArrayList<Processor>(1);
        answer.add(nextProcessor);
        return answer;
    }

    public boolean hasNext() {
        return nextProcessor != null;
    }

    public void setNextProcessor(Processor next) {
        this.nextProcessor = next;
    }

    public Processor getOutput() {
        // the errorHandler is already decorated with interceptors
        // so it contain the entire chain of processors, so we can safely use it directly as output
        // if no error handler provided we use the output
        // TODO: Camel 3.0 we should determine the output dynamically at runtime instead of having the
        // the error handlers, interceptors, etc. woven in at design time
        return errorHandler != null ? errorHandler : output;
    }

    public void setOutput(Processor output) {
        this.output = output;
    }

    public Processor getNextProcessor() {
        return nextProcessor;
    }

    public boolean hasInterceptorStrategy(Class<?> type) {
        for (InterceptStrategy strategy : interceptors) {
            if (type.isInstance(strategy)) {
                return true;
            }
        }
        return false;
    }

    public void setErrorHandler(Processor errorHandler) {
        this.errorHandler = errorHandler;
    }

    public Processor getErrorHandler() {
        return errorHandler;
    }

    public void addInterceptStrategy(InterceptStrategy strategy) {
        interceptors.add(strategy);
    }

    public void addInterceptStrategies(List<InterceptStrategy> strategies) {
        interceptors.addAll(strategies);
    }

    public List<InterceptStrategy> getInterceptStrategies() {
        return interceptors;
    }

    public ProcessorDefinition<?> getProcessorDefinition() {
        return definition;
    }

    public void setChildDefinition(ProcessorDefinition<?> childDefinition) {
        this.childDefinition = childDefinition;
    }

    public RouteContext getRouteContext() {
        return routeContext;
    }

    @Override
    protected void doStart() throws Exception {
        // create route context processor to wrap output
        routeContextProcessor = new RouteContextProcessor(routeContext, getOutput());
        ServiceHelper.startServices(errorHandler, output, routeContextProcessor);
    }

    @Override
    protected void doStop() throws Exception {
        ServiceHelper.stopServices(output, errorHandler, routeContextProcessor);
    }

    public void initChannel(ProcessorDefinition<?> outputDefinition, RouteContext routeContext) throws Exception {
        this.routeContext = routeContext;
        this.definition = outputDefinition;
        this.camelContext = routeContext.getCamelContext();

        Processor target = nextProcessor;
        Processor next;

        // init CamelContextAware as early as possible on target
        if (target instanceof CamelContextAware) {
            ((CamelContextAware) target).setCamelContext(camelContext);
        }

        // the definition to wrap should be the fine grained,
        // so if a child is set then use it, if not then its the original output used
        ProcessorDefinition<?> targetOutputDef = childDefinition != null ? childDefinition : outputDefinition;
        LOG.debug("Initialize channel for target: '{}'", targetOutputDef);

        // fix parent/child relationship. This will be the case of the routes has been
        // defined using XML DSL or end user may have manually assembled a route from the model.
        // Background note: parent/child relationship is assembled on-the-fly when using Java DSL (fluent builders)
        // where as when using XML DSL (JAXB) then it fixed after, but if people are using custom interceptors
        // then we need to fix the parent/child relationship beforehand, and thus we can do it here
        // ideally we need the design time route -> runtime route to be a 2-phase pass (scheduled work for Camel 3.0)
        if (childDefinition != null && outputDefinition != childDefinition) {
            childDefinition.setParent(outputDefinition);
        }

        // first wrap the output with the managed strategy if any
        InterceptStrategy managed = routeContext.getManagedInterceptStrategy();
        if (managed != null) {
            next = target == nextProcessor ? null : nextProcessor;
            target = managed.wrapProcessorInInterceptors(routeContext.getCamelContext(), targetOutputDef, target, next);
        }

        // then wrap the output with the backlog and tracer (backlog first, as we do not want regular tracer to tracer the backlog)
        BacklogTracerInterceptor backlog = (BacklogTracerInterceptor) getOrCreateBacklogTracer().wrapProcessorInInterceptors(routeContext.getCamelContext(), targetOutputDef, target, null);
        TraceInterceptor trace = (TraceInterceptor) getOrCreateTracer().wrapProcessorInInterceptors(routeContext.getCamelContext(), targetOutputDef, backlog, null);
        // trace interceptor need to have a reference to route context so we at runtime can enable/disable tracing on-the-fly
        trace.setRouteContext(routeContext);
        target = trace;

        // sort interceptors according to ordered
        Collections.sort(interceptors, new OrderedComparator());
        // then reverse list so the first will be wrapped last, as it would then be first being invoked
        Collections.reverse(interceptors);
        // wrap the output with the configured interceptors
        for (InterceptStrategy strategy : interceptors) {
            next = target == nextProcessor ? null : nextProcessor;
            // skip tracer as we did the specially beforehand and it could potentially be added as an interceptor strategy
            if (strategy instanceof Tracer) {
                continue;
            }
            // skip stream caching as it must be wrapped as outer most, which we do later
            if (strategy instanceof StreamCaching) {
                continue;
            }
            // use the fine grained definition (eg the child if available). Its always possible to get back to the parent
            Processor wrapped = strategy.wrapProcessorInInterceptors(routeContext.getCamelContext(), targetOutputDef, target, next);
            if (!(wrapped instanceof AsyncProcessor)) {
                LOG.warn("Interceptor: " + strategy + " at: " + outputDefinition + " does not return an AsyncProcessor instance."
                        + " This causes the asynchronous routing engine to not work as optimal as possible."
                        + " See more details at the InterceptStrategy javadoc."
                        + " Camel will use a bridge to adapt the interceptor to the asynchronous routing engine,"
                        + " but its not the most optimal solution. Please consider changing your interceptor to comply.");

                // use a bridge and wrap again which allows us to adapt and leverage the asynchronous routing engine anyway
                // however its not the most optimal solution, but we can still run.
                InterceptorToAsyncProcessorBridge bridge = new InterceptorToAsyncProcessorBridge(target);
                wrapped = strategy.wrapProcessorInInterceptors(routeContext.getCamelContext(), targetOutputDef, bridge, next);
                bridge.setTarget(wrapped);
                wrapped = bridge;
            }
            // ensure target gets wrapped so we can control its lifecycle
            if (!(wrapped instanceof WrapProcessor)) {
                wrapped = new WrapProcessor(wrapped, target);
            }
            target = wrapped;
        }

        // sets the delegate to our wrapped output
        output = target;
    }

    @Override
    public void postInitChannel(ProcessorDefinition<?> outputDefinition, RouteContext routeContext) throws Exception {
        for (InterceptStrategy strategy : interceptors) {
            // apply stream caching at the end as it should be outer most
            if (strategy instanceof StreamCaching) {
                if (errorHandler != null) {
                    errorHandler = strategy.wrapProcessorInInterceptors(routeContext.getCamelContext(), outputDefinition, errorHandler, null);
                } else {
                    output = strategy.wrapProcessorInInterceptors(routeContext.getCamelContext(), outputDefinition, output, null);
                }
                break;
            }
        }
    }

    private InterceptStrategy getOrCreateTracer() {
        InterceptStrategy tracer = Tracer.getTracer(camelContext);
        if (tracer == null) {
            if (camelContext.getRegistry() != null) {
                // lookup in registry
                Map<String, Tracer> map = camelContext.getRegistry().findByTypeWithName(Tracer.class);
                if (map.size() == 1) {
                    tracer = map.values().iterator().next();
                }
            }
            if (tracer == null) {
                // fallback to use the default tracer
                tracer = camelContext.getDefaultTracer();

                // configure and use any trace formatter if any exists
                Map<String, TraceFormatter> formatters = camelContext.getRegistry().findByTypeWithName(TraceFormatter.class);
                if (formatters.size() == 1) {
                    TraceFormatter formatter = formatters.values().iterator().next();
                    if (tracer instanceof Tracer) {
                        ((Tracer) tracer).setFormatter(formatter);
                    }
                }
            }
        }

        // which we must manage as well
        for (LifecycleStrategy strategy : camelContext.getLifecycleStrategies()) {
            if (tracer instanceof Service) {
                strategy.onServiceAdd(camelContext, (Service) tracer, null);
            }
        }

        return tracer;
    }

    private InterceptStrategy getOrCreateBacklogTracer() {
        InterceptStrategy tracer = BacklogTracer.getBacklogTracer(camelContext);
        if (tracer == null) {
            if (camelContext.getRegistry() != null) {
                // lookup in registry
                Map<String, BacklogTracer> map = camelContext.getRegistry().findByTypeWithName(BacklogTracer.class);
                if (map.size() == 1) {
                    tracer = map.values().iterator().next();
                }
            }
            if (tracer == null) {
                // fallback to use the default tracer
                tracer = camelContext.getDefaultBacklogTracer();
            }
        }

        // which we must manage as well
        for (LifecycleStrategy strategy : camelContext.getLifecycleStrategies()) {
            if (tracer instanceof Service) {
                strategy.onServiceAdd(camelContext, (Service) tracer, null);
            }
        }

        return tracer;
    }

    public void process(Exchange exchange) throws Exception {
        AsyncProcessorHelper.process(this, exchange);
    }

    public boolean process(final Exchange exchange, final AsyncCallback callback) {
        Processor processor = getOutput();
        if (processor == null || !continueProcessing(exchange)) {
            // we should not continue routing so we are done
            callback.done(true);
            return true;
        }

        // process the exchange using the route context processor
        ObjectHelper.notNull(routeContextProcessor, "RouteContextProcessor", this);
        return routeContextProcessor.process(exchange, callback);
    }

    /**
     * Strategy to determine if we should continue processing the {@link Exchange}.
     */
    protected boolean continueProcessing(Exchange exchange) {
        Object stop = exchange.getProperty(Exchange.ROUTE_STOP);
        if (stop != null) {
            boolean doStop = exchange.getContext().getTypeConverter().convertTo(Boolean.class, stop);
            if (doStop) {
                LOG.debug("Exchange is marked to stop routing: {}", exchange);
                return false;
            }
        }

        // determine if we can still run, or the camel context is forcing a shutdown
        boolean forceShutdown = camelContext.getShutdownStrategy().forceShutdown(this);
        if (forceShutdown) {
            LOG.debug("Run not allowed as ShutdownStrategy is forcing shutting down, will reject executing exchange: {}", exchange);
            if (exchange.getException() == null) {
                exchange.setException(new RejectedExecutionException());
            }
            return false;
        }

        // yes we can continue
        return true;
    }

    @Override
    public String toString() {
        // just output the next processor as all the interceptors and error handler is just too verbose
        return "Channel[" + nextProcessor + "]";
    }

}
