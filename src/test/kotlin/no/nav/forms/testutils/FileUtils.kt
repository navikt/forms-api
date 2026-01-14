package no.nav.forms.testutils

import no.nav.forms.TestFormsApi

class FileUtils {

	companion object {
		fun loadBytes(fileName: String): ByteArray {
			val stream = FileUtils::class.java.getResourceAsStream("/files/$fileName")
				?: throw IllegalArgumentException("File not found: $fileName")
			return stream.readBytes()
		}
	}
}
