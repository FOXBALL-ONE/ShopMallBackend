package top.foxball.shopmall.logging

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.util.ErrorHandler
import java.util.concurrent.atomic.AtomicBoolean

/** Pub/Sub makes cross-node settings convergence prompt; scheduled reconciliation remains the fallback. */
@Configuration(proxyBeanMethods = false)
class LoggingSettingsSubscriber {
    private val log = LoggerFactory.getLogger(javaClass)
    private val reconcileRequested = AtomicBoolean(false)
    private val reconcileScheduled = AtomicBoolean(false)

    @Bean
    fun loggingSettingsMessageListener(
        runtimeLoggingManager: RuntimeLoggingManager,
        @Qualifier(LoggingConfig.LOGGING_TASK_SCHEDULER)
        loggingTaskScheduler: ThreadPoolTaskScheduler,
    ): MessageListener = MessageListener { _, _ ->
        reconcileRequested.set(true)
        scheduleReconcile(runtimeLoggingManager, loggingTaskScheduler)
    }

    @Bean
    fun loggingSettingsMessageListenerContainer(
        connectionFactory: RedisConnectionFactory,
        loggingSettingsMessageListener: MessageListener,
    ): RedisMessageListenerContainer = RedisMessageListenerContainer().apply {
        setConnectionFactory(connectionFactory)
        setErrorHandler(ErrorHandler { })
        setRecoveryInterval(5_000)
        setMaxSubscriptionRegistrationWaitingTime(1_000)
        addMessageListener(loggingSettingsMessageListener, ChannelTopic(LoggingSettingsService.SETTINGS_CHANGED_CHANNEL))
    }

    /** Coalesces bursts of Pub/Sub notifications and keeps Redis I/O off the listener thread. */
    private fun scheduleReconcile(
        runtimeLoggingManager: RuntimeLoggingManager,
        loggingTaskScheduler: ThreadPoolTaskScheduler,
    ) {
        if (!reconcileScheduled.compareAndSet(false, true)) return
        try {
            loggingTaskScheduler.execute {
                try {
                    do {
                        reconcileRequested.set(false)
                        runtimeLoggingManager.reconcile()
                    } while (reconcileRequested.get())
                } finally {
                    reconcileScheduled.set(false)
                    if (reconcileRequested.get()) {
                        scheduleReconcile(runtimeLoggingManager, loggingTaskScheduler)
                    }
                }
            }
        } catch (exception: RuntimeException) {
            reconcileScheduled.set(false)
            log.debug("Unable to schedule ShopMall logging settings reconciliation", exception)
        }
    }
}
