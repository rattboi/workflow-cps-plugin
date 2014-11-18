/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.cps;

import com.google.common.util.concurrent.FutureCallback;
import hudson.model.Action;
import org.jenkinsci.plugins.workflow.cps.persistence.PersistIn;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.WorkflowBodyInvoker;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.jenkinsci.plugins.workflow.cps.persistence.PersistenceContext.*;

/**
 * Builder pattern for accumulating configuration for executing the body.
 *
 * <p>
 * After various {@code withXyz} methods are invoked, {@link #start()} gets called
 * to schedule the execution. The actual state update happens from the {@link #launch(CpsThread,FlowHead)}
 * method, which is {@link CpsVmThreadOnly}.
 *
 * @see CpsBodyExecution
 * @author Kohsuke Kawaguchi
 */
@PersistIn(NONE)
public final class CpsBodyInvoker extends WorkflowBodyInvoker {
    /*package*/ final List<Object> contextOverrides = new ArrayList<Object>();

    /*package*/ final BodyReference body;

    private final CpsStepContext owner;

    private List<BodyExecutionCallback> callbacks = new ArrayList<BodyExecutionCallback>();

    /*package*/ final List<Action> startNodeActions = new ArrayList<Action>();

    private String displayName;

    private boolean createBodyStartNode = true;

    private boolean started;

    /**
     * Set to non-null once {@linkplain #start() started}.
     */
    private CpsBodyExecution execution;

    CpsBodyInvoker(CpsStepContext owner, BodyReference body) {
        this.owner = owner;
        this.body = body;
    }

    @Override
    public CpsBodyInvoker withContext(Object override) {
        contextOverrides.add(override);
        return this;
    }

    @Override
    public CpsBodyInvoker withStartAction(Action a) {
        startNodeActions.add(a);
        return this;
    }

    public void prependCallback(BodyExecutionCallback callback) {
        callbacks.add(0, callback);
    }

    @Override
    public CpsBodyInvoker withCallback(BodyExecutionCallback callback) {
        callbacks.add(callback);
        return this;
    }

    @Override
    public CpsBodyInvoker withDisplayName(@Nullable String name) {
        this.displayName = name;
        createBodyStartNode = (name==null);
        return this;
    }

    /**
     * Schedules the execution of the body.
     *
     * The actual launching of the body will be done later in {@link #launch} methods.
     */
    @Override
    public CpsBodyExecution start() {
        if (execution!=null)    throw new IllegalStateException("Already started");
        execution = new CpsBodyExecution(owner, callbacks);

        if (owner.isSyncMode()) {
            // we call 'launch' later from DSL.ThreadTaskImpl
            owner.bodyInvokers.add(this);
        } else {
            try {
                owner.getExecution().runInCpsVmThread(new FutureCallback<CpsThreadGroup>() {
                    @Override
                    public void onSuccess(CpsThreadGroup g) {
                        CpsThread thread = owner.getThread(g);
                        if (thread != null) {
                            launch(thread);
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        execution.onFailure.receive(t);
                    }
                });
            } catch (IOException e) {
                execution.onFailure.receive(e);
            }
        }

        return execution;
    }

    /**
     * Evaluates the body but grow the {@link FlowNode}s on the same head as the current thread.
     *
     * The net effect is as if the body evaluation happens in the same thread as in the caller thread.
     */
    @CpsVmThreadOnly
    /*package*/ void launch(CpsThread currentThread) {
        launch(currentThread, currentThread.head);
    }

    @CpsVmThreadOnly
    /*package*/ void launch(CpsThread currentThread, FlowHead head) {
        execution.launch(this, currentThread, head);
    }
}
