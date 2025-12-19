package no.nav.forms.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("gcp.buckets.static-pdfs")
class StaticPdfConfig {

	lateinit var bucketName: String

}
