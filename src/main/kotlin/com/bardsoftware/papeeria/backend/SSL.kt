/**
Copyright 2020 BarD Software s.r.o

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package com.bardsoftware.papeeria.backend

import com.google.cloud.ServiceOptions
import com.google.cloud.secretmanager.v1.AccessSecretVersionRequest
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient.create
import com.google.cloud.secretmanager.v1.SecretVersionName
import org.slf4j.LoggerFactory
import java.io.*
import java.util.function.Supplier

/**
 * Enumerates the given urls and returns the first one where input stream can be
 * created. URLs can be file paths or our special home-grown ones for accessing
 * secrets from Secret Manager. Secret URLS look like "secret:SECRET_ID:VERSION_ID".
 * The last component is secret version and it may be "latest" to indicate the latest
 * available secret.
 *
 * See https://cloud.google.com/secret-manager/docs
 * @param urls
 * @return
 */
fun getStream(vararg urls: String?): Supplier<InputStream>? {
  for (url in urls) {
    if (url == null) {
      continue
    }
    LOGGER.info("Trying {}", url)
    if (url.startsWith("secret:")) {
      val components = url.split(":".toRegex()).toTypedArray()
      if (components.size != 3) {
        LOGGER.warn("Secret Manager URLs are supposed to look like secret:SECRET_ID:VERSION_ID")
        continue
      }
      return readSecret(components[1], components[2])
    }

    val f = File(url)
    if (f.exists() && f.canRead()) {
      return Supplier {
        try {
          FileInputStream(f)
        } catch (e: FileNotFoundException) {
          LOGGER.error("File {} was reportedly existing and readable. However, we've caught this:", url, e)
          throw RuntimeException(e)
        }
      }
    }
  }
  return null
}

private fun getProjectId(): String? {
  return ServiceOptions.getDefaultProjectId()
}

private fun readSecret(secretId: String, versionId: String): Supplier<InputStream>? {
  try {
    create().use { client ->
      val name: SecretVersionName = SecretVersionName.of(getProjectId(), secretId, versionId)
      // Access the secret version.
      val request: AccessSecretVersionRequest = AccessSecretVersionRequest.newBuilder().setName(name.toString()).build()
      val response: AccessSecretVersionResponse = client.accessSecretVersion(request)
      return Supplier { response.payload.data.newInput() }
    }
  } catch (e: IOException) {
    LOGGER.error("IOException when reading secret {}", secretId, e)
    return null
  }
}
private val LOGGER = LoggerFactory.getLogger("base.server")
