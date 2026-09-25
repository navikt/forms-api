package no.nav.forms.cleanup

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import org.springframework.web.filter.OncePerRequestFilter

class DatabaseCleanupGuard(private val environment: Environment) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (environment.activeProfiles.contains("prod") || environment.getProperty("NAIS_CLUSTER_NAME") == "prod-gcp") {
            response.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        chain.doFilter(request, response)
    }
}

@Configuration
class DatabaseCleanupGuardConfig {
    @Bean
    fun databaseCleanupGuard(environment: Environment): FilterRegistrationBean<DatabaseCleanupGuard> =
        FilterRegistrationBean(DatabaseCleanupGuard(environment)).apply {
            addUrlPatterns("/api/database-cleanup/*")
            order = Ordered.HIGHEST_PRECEDENCE
        }
}
