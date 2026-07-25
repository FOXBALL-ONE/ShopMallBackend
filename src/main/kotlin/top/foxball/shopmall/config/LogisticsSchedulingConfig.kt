package top.foxball.shopmall.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@Configuration
@EnableScheduling
class LogisticsSchedulingConfig {
    @Bean
    fun utcClock(): Clock = Clock.systemUTC()
}
