/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.common.taskrunner;

import haveno.common.handlers.ErrorMessageHandler;
import haveno.common.handlers.ResultHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class TaskRunner<T extends Model> {
    private final Queue<Class<? extends Task<T>>> tasks = new LinkedBlockingQueue<>();
    private final T sharedModel;
    private final Class<T> sharedModelClass;
    private final ResultHandler resultHandler;
    private final ErrorMessageHandler errorMessageHandler;
    private boolean failed = false;
    private boolean isCanceled;

    private Class<? extends Task<T>> currentTask;


    public TaskRunner(T sharedModel, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        //noinspection unchecked
        this(sharedModel, (Class<T>) sharedModel.getClass(), resultHandler, errorMessageHandler);
    }

    public TaskRunner(T sharedModel, Class<T> sharedModelClass, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        this.sharedModel = sharedModel;
        this.resultHandler = resultHandler;
        this.errorMessageHandler = errorMessageHandler;
        this.sharedModelClass = sharedModelClass;
    }

    @SafeVarargs
    public final void addTasks(Class<? extends Task<T>>... items) {
        tasks.addAll(Arrays.asList(items));
    }

    public void run() {
        next();
    }

    private void next() {
        if (!failed && !isCanceled) {
            if (tasks.size() > 0) {
                try {
                    currentTask = tasks.poll();
                    log.info("Run task: " + currentTask.getSimpleName());
                    currentTask.getDeclaredConstructor(TaskRunner.class, sharedModelClass).newInstance(this, sharedModel).run();
                } catch (Throwable throwable) {
                    throwable.printStackTrace();
                    handleErrorMessage("Error at taskRunner: " + throwable.getMessage());
                }
            } else {
                resultHandler.handleResult();
            }
        }
    }

    public void cancel() {
        isCanceled = true;
    }

    void handleComplete() {
        next();
    }

    void handleErrorMessage(String errorMessage) {
        log.error("Task failed: " + currentTask.getSimpleName() + " / errorMessage: " + errorMessage);
        failed = true;
        errorMessageHandler.handleErrorMessage(errorMessage);
    }
}
