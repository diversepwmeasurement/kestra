package io.kestra.plugin.core.flow;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.executions.NextTaskRun;
import io.kestra.core.models.executions.TaskRun;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.hierarchies.AbstractGraph;
import io.kestra.core.models.hierarchies.GraphCluster;
import io.kestra.core.models.hierarchies.RelationType;
import io.kestra.core.models.tasks.FlowableTask;
import io.kestra.core.models.tasks.ResolvedTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.FlowableUtils;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.GraphUtils;
import io.kestra.core.utils.TruthUtils;
import io.micronaut.core.annotation.Introspected;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a specific task repeatedly until the expected condition is met.",
    description = """
        Use this task if your downstream processing requires waiting for a specific HTTP response or a job to finish.
        You can access the task output in the `condition`.

        The `condition` is always checked after the task execution.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Wait for a task to return a specific output",
            code = """
                id: exampleFlow
                namespace: myteam

                tasks:
                  - id: waitFor
                    type: io.kestra.plugin.core.flow.WaitFor
                    condition: "{{ outputs.return.value != '4' }}"
                    task:
                      id: return
                      type: io.kestra.plugin.core.debug.Return
                      format: "{{ outputs.waitFor.iterationCount }}"
                """
        )
    }
)
public class WaitFor extends Task implements FlowableTask<WaitFor.Output> {
    @Valid
    protected List<Task> errors;

    @Valid
    @PluginProperty
    @NotNull
    private List<Task> tasks;

    @NotNull
    @PluginProperty(dynamic = true)
    @Schema(
        title = "The condition to execute again the task that must be a boolean.",
        description = "Boolean coercion allows 0, -0, null and '' to evaluate to false, all other values will evaluate to true."
    )
    private String condition;

    @Schema(
        title = "If true, the task will fail if the `maxIterations` or `maxDuration` are reached."
    )
    @Builder.Default
    private Boolean failOnMaxReached = false;

    @Schema(
        title = "Check frequency configuration."
    )
    @Builder.Default
    @PluginProperty
    private CheckFrequency checkFrequency = CheckFrequency.builder().build();

    @Override
    public AbstractGraph tasksTree(Execution execution, TaskRun taskRun, List<String> parentValues) throws IllegalVariableEvaluationException {
        GraphCluster subGraph = new GraphCluster(this, taskRun, parentValues, RelationType.SEQUENTIAL);

        GraphUtils.sequential(
            subGraph,
            tasks,
            this.errors,
            taskRun,
            execution
        );

        return subGraph;
    }

    @Override
    public List<Task> allChildTasks() {
        return Stream
            .concat(
                tasks.stream(),
                this.getErrors() != null ? this.getErrors().stream() : Stream.empty()
            )
            .collect(Collectors.toList());
    }

    @Override
    public List<ResolvedTask> childTasks(RunContext runContext, TaskRun parentTaskRun) throws IllegalVariableEvaluationException {
        return FlowableUtils.resolveTasks(tasks, parentTaskRun);
    }

    @Override
    public List<NextTaskRun> resolveNexts(RunContext runContext, Execution execution, TaskRun parentTaskRun) throws IllegalVariableEvaluationException {

        return FlowableUtils.resolveWaitForNext(
            execution,
            this.childTasks(runContext, parentTaskRun),
            FlowableUtils.resolveTasks(this.getErrors(), parentTaskRun),
            parentTaskRun
        );
    }

    public Instant nextExecutionDate(RunContext runContext, Execution execution, TaskRun parentTaskRun) throws IllegalVariableEvaluationException {
        if (!this.reachedMaximums(runContext, execution, parentTaskRun, false)) {
            String continueLoop = runContext.render(this.condition);
            if (TruthUtils.isTruthy(continueLoop)) {

                return Instant.now().plus(this.checkFrequency.interval);
            }
        }

        return null;
    }

    private boolean reachedMaximums(RunContext runContext, Execution execution, TaskRun parentTaskRun, Boolean printLog) {
        Logger logger = runContext.logger();

        if (!this.childTaskRunExecuted(execution, parentTaskRun)) {
            return false;
        }

        Integer iterationCount = (Integer) parentTaskRun.getOutputs().get("iterationCount");
        if (this.checkFrequency.maxIterations != null && iterationCount != null && iterationCount >= this.checkFrequency.maxIterations) {
            if (printLog) {logger.warn("Max iterations reached");}
            return true;
        }

        Instant creationDate = parentTaskRun.getState().getHistories().getFirst().getDate();
        if (this.checkFrequency.maxDuration != null &&
            creationDate != null && creationDate.plus(this.checkFrequency.maxDuration).isBefore(Instant.now())) {
            if (printLog) {logger.warn("Max duration reached");}

            return true;
        }

        return false;
    }

    @Override
    public Optional<State.Type> resolveState(RunContext runContext, Execution execution, TaskRun parentTaskRun) throws IllegalVariableEvaluationException {
        boolean childTaskExecuted = this.childTaskRunExecuted(execution, parentTaskRun);
        if (childTaskExecuted && nextExecutionDate(runContext, execution, parentTaskRun) != null) {
            return Optional.of(State.Type.RUNNING);
        }

        if (childTaskExecuted && this.reachedMaximums(runContext, execution, parentTaskRun, true) && this.failOnMaxReached) {
            return Optional.of(State.Type.FAILED);
        }

        return FlowableUtils.resolveState(
            execution,
            this.childTasks(runContext, parentTaskRun),
            FlowableUtils.resolveTasks(this.getErrors(), parentTaskRun),
            parentTaskRun,
            runContext,
            isAllowFailure()
        );
    }

    public Optional<TaskRun> getChildTaskRun(Execution execution, TaskRun parentTaskRun) {
        if (execution.getTaskRunList() == null) {
            return Optional.empty();
        }
        return execution
            .getTaskRunList()
            .stream()
            .filter(t -> t.getParentTaskRunId() != null && t.getParentTaskRunId().equals(parentTaskRun.getId()) && t.getState().isSuccess())
            .findFirst();
    }

    public boolean childTaskRunExecuted(Execution execution, TaskRun parentTaskRun) {
        if (execution.getTaskRunList() == null) {
            return false;
        }
        return execution
            .getTaskRunList()
            .stream()
            .filter(t -> t.getParentTaskRunId() != null
                && t.getParentTaskRunId().equals(parentTaskRun.getId()) && t.getState().isSuccess()
                && t.getState().isTerminated()
            ).count() == tasks.size();

    }

    @Override
    public WaitFor.Output outputs(RunContext runContext) throws IllegalVariableEvaluationException {
       Map<String, Object> outputs = (Map<String, Object>) runContext.getVariables().get("outputs");
        if (outputs != null && outputs.get(this.id) != null) {
            return Output.builder().iterationCount((Integer) ((Map<String, Object>) outputs.get(this.id)).get("iterationCount")).build();
        }
        return WaitFor.Output.builder()
            .iterationCount(1)
            .build();
    }

    public WaitFor.Output outputs(TaskRun parentTaskRun) throws IllegalVariableEvaluationException {
        String value = parentTaskRun != null ?
            parentTaskRun.getOutputs().get("iterationCount").toString() : "0";

        return Output.builder()
            .iterationCount(Integer.parseInt(value) + 1)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        private Integer iterationCount;
    }

    @SuperBuilder(toBuilder = true)
    @Introspected
    @Getter
    @NoArgsConstructor
    public static class CheckFrequency {
        @Schema(
            title = "Maximum count of iterations."
        )
        @Builder.Default
        @PluginProperty
        private Integer maxIterations = 100;

        @Schema(
            title = "Maximum duration of the task."
        )
        @Builder.Default
        @PluginProperty
        private Duration maxDuration = Duration.ofHours(1);

        @Schema(
            title = "Interval between each iteration."
        )
        @Builder.Default
        @PluginProperty
        private Duration interval = Duration.ofSeconds(1);
    }
}
