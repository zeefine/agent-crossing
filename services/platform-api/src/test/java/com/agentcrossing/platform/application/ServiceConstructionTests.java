package com.agentcrossing.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.agentcrossing.platform.application.chat.ChatService;
import com.agentcrossing.platform.application.chat.ThreadCancellationService;
import com.agentcrossing.platform.application.chat.ThreadPlanningQueue;
import com.agentcrossing.platform.application.invocation.ExecutionStateService;
import com.agentcrossing.platform.application.invocation.InvocationService;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ServiceConstructionTests {
    static Class<?>[] executionConsumers() {
        return new Class<?>[] {InvocationService.class, ThreadCancellationService.class};
    }

    @ParameterizedTest
    @MethodSource("executionConsumers")
    void executionStateIsSuppliedAtConstructionAndCannotBeReplaced(Class<?> type) throws Exception {
        assertThat(type.getDeclaredConstructors()).hasSize(1);
        var constructor = type.getDeclaredConstructors()[0];
        assertThat(constructor.getParameterTypes()).contains(ExecutionStateService.class);
        var state = mock(ExecutionStateService.class);
        Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(parameter -> parameter == ExecutionStateService.class ? state
                        : parameter == String.class ? "http://unused" : dependency(parameter))
                .toArray();

        Object service = constructor.newInstance(arguments);

        assertThat(ReflectionTestUtils.getField(service, "executionStateService")).isSameAs(state);
        assertThat(Modifier.isFinal(type.getDeclaredField("executionStateService").getModifiers())).isTrue();
        assertThat(type.getDeclaredMethods()).noneMatch(method -> method.getName().equals("setExecutionStateService"));
    }

    @Test
    void chatServiceHasOneExplicitProductionConstructor() {
        assertThat(ChatService.class.getDeclaredConstructors()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void springConsumersShareTheConfiguredExecutionStateBean(boolean withTransactions) {
        try (var context = new AnnotationConfigApplicationContext()) {
            Class<?>[] services = {InvocationService.class, ThreadCancellationService.class, ChatService.class};
            Arrays.stream(services).flatMap(type -> Arrays.stream(type.getDeclaredConstructors()[0].getParameterTypes()))
                    .distinct()
                    .filter(type -> type != String.class && type != ObjectProvider.class && type != ExecutionStateService.class)
                    .forEach(type -> context.getBeanFactory().registerSingleton(
                            type == java.util.concurrent.Executor.class ? "chatPlanningExecutor" : type.getSimpleName(),
                            dependency(type)));
            var manager = mock(PlatformTransactionManager.class);
            var transaction = new SimpleTransactionStatus();
            if (withTransactions) {
                when(manager.getTransaction(any())).thenReturn(transaction);
                context.getBeanFactory().registerSingleton("transactionManager", manager);
            }
            context.register(ExecutionStateService.class);
            context.register(services);
            context.refresh();

            var state = context.getBean(ExecutionStateService.class);
            assertThat(context.getBeansOfType(ExecutionStateService.class)).hasSize(1);
            assertThat(ReflectionTestUtils.getField(context.getBean(InvocationService.class), "executionStateService"))
                    .isSameAs(state);
            assertThat(ReflectionTestUtils.getField(context.getBean(ThreadCancellationService.class), "executionStateService"))
                    .isSameAs(state);
            var chat = context.getBean(ChatService.class);
            assertThat(ReflectionTestUtils.getField(chat, "threadPlanningQueue"))
                    .isSameAs(context.getBean(ThreadPlanningQueue.class));
            TransactionTemplate chatTransactions = (TransactionTemplate) ReflectionTestUtils.getField(chat, "transactionTemplate");
            state.cancelTrace("user", "trace");
            if (withTransactions) {
                assertThat(chatTransactions.getTransactionManager()).isSameAs(manager);
                verify(manager).getTransaction(any());
                verify(manager).commit(transaction);
            } else {
                assertThat(chatTransactions).isNull();
                assertThat(ReflectionTestUtils.getField(state, "transactions")).isNull();
            }
        }
    }

    private static Object dependency(Class<?> type) {
        return type == ThreadPlanningQueue.class ? new ThreadPlanningQueue(Runnable::run) : mock(type);
    }
}
