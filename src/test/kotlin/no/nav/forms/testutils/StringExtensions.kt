package no.nav.forms.testutils

import org.springframework.web.util.UriComponentsBuilder
import java.net.URI

fun String.toURI(): URI {
	val uri = UriComponentsBuilder.fromUriString(this)
		.build()
		.toUri()
	return uri
}
