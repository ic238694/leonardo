package org.broadinstitute.dsde.workbench.leonardo.notebooks

import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.ResourceFile
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.leonardo.TestUser.{getAuthTokenAndAuthorization, Ron}
import org.broadinstitute.dsde.workbench.leonardo.{
  BillingProjectFixtureSpec,
  LeonardoApiClient,
  UserJupyterExtensionConfig
}
import org.scalatest.{DoNotDiscover, ParallelTestExecution}

import scala.concurrent.duration._

/**
 * This spec verifies different cluster creation options, such as extensions, scopes, environment variables.
 *
 * TODO consider removing this spec and moving test cases to RuntimeGceSpec.
 */
@DoNotDiscover
final class NotebookGCECustomizationSpec
    extends BillingProjectFixtureSpec
    with ParallelTestExecution
    with NotebookTestUtils {
  implicit val (ronAuthToken, ronAuthorization) = getAuthTokenAndAuthorization(Ron)
  implicit def ronToken: AuthToken = ronAuthToken.unsafeRunSync()

  "NotebookGCECustomizationSpec" - {
    // Using nbtranslate extension from here:
    // https://github.com/ipython-contrib/jupyter_contrib_nbextensions/tree/master/src/jupyter_contrib_nbextensions/nbextensions/nbTranslate
    "should install user specified notebook extensions" in { billingProject =>
      val translateExtensionFile = ResourceFile("bucket-tests/translate_nbextension.tar.gz")
      withResourceFileInBucket(billingProject, translateExtensionFile, "application/x-gzip") {
        translateExtensionBucketPath =>
          val extensionConfig = UserJupyterExtensionConfig(
            nbExtensions = Map("translate" -> translateExtensionBucketPath.toUri)
          )
          withNewRuntime(
            billingProject,
            request =
              LeonardoApiClient.defaultCreateRuntime2Request.copy(userJupyterExtensionConfig = Some(extensionConfig))
          ) { runtime =>
            withWebDriver { implicit driver =>
              withNewNotebook(runtime, Python3) { notebookPage =>
                // Check the extensions were installed
                val nbExt = notebookPage.executeCell("! jupyter nbextension list")

                nbExt.get should include("translate_nbextension/main  enabled")
                // should be installed by default
                nbExt.get should include("toc2/main  enabled")

                val serverExt = notebookPage.executeCell("! jupyter serverextension list")
                serverExt.get should include("jupyterlab  enabled")
                // should be installed by default
                serverExt.get should include("jupyter_nbextensions_configurator  enabled")

                // Exercise the translate extensionfailure_screenshots/NotebookGCECustomizationSpec_18-34-37-017.png
                notebookPage.translateMarkup("Yes") should include("Oui")
              }
            }
          }
      }
    }

    "should give cluster user-specified scopes" in { billingProject =>
      withNewRuntime(
        billingProject,
        request = LeonardoApiClient.defaultCreateRuntime2Request.copy(
          scopes = Set(
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/source.read_only",
            "https://www.googleapis.com/auth/devstorage.full_control"
          )
        )
      ) { cluster =>
        withWebDriver { implicit driver =>
          // With Scopes
          withNewNotebook(cluster) { notebookPage =>
            val query =
              """! bq query --disable_ssl_validation --format=json "SELECT COUNT(*) AS scullion_count FROM publicdata.samples.shakespeare WHERE word='scullion'" """

            // Result should fail due to insufficient scopes.
            // Note we used to check for 'Invalid credential' in the result but the error message from
            // Google does not seem stable.
            val result = notebookPage.executeCell(query, timeout = 5.minutes).get
            result should include("BigQuery error in query operation")
            result should not include "scullion_count"
          }
        }
      }
    }

    "should populate user-specified environment variables" in { billingProject =>
      // Note: the R image includes R and Python 3 kernels
      val runtimeRequest =
        LeonardoApiClient.defaultCreateRuntime2Request.copy(customEnvironmentVariables = Map("KEY" -> "value"))

      withNewRuntime(billingProject, request = runtimeRequest) { cluster =>
        withWebDriver { implicit driver =>
          withNewNotebook(cluster, Python3) { notebookPage =>
            notebookPage.executeCell("import os")

            val envVar = notebookPage.executeCell("os.getenv('KEY')")
            envVar shouldBe Some("'value'")
          }
        }
      }
    }
  }
}
