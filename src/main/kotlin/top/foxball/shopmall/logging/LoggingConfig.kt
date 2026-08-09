package top.foxball.shopmall.logging

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/** Registers logging configuration properties and periodic settings reconciliation. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(LoggingProperties::class)
class LoggingConfig {
    /**
     * Logging maintenance must not use Spring's default scheduler. Redis reconciliation and
     * retention scans can both wait on external storage, so keeping them on their own daemon
     * threads prevents a slow log directory or Redis node from delaying business schedules.
     */
    @Bean(name = [LOGGING_TASK_SCHEDULER], defaultCandidate = false)
    fun loggingTaskScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        setPoolSize(LOGGING_TASK_SCHEDULER_POOL_SIZE)
        setThreadNamePrefix("shopmall-log-maintenance-")
        setDaemon(true)
        setWaitForTasksToCompleteOnShutdown(false)
        setAwaitTerminationMillis(0)
        setRemoveOnCancelPolicy(true)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    companion object {
        const val LOGGING_TASK_SCHEDULER = "loggingTaskScheduler"
        private const val LOGGING_TASK_SCHEDULER_POOL_SIZE = 2
    }
}
