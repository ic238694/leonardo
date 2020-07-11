package org.broadinstitute.dsde.workbench.leonardo.config

import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.NamespaceName
import org.broadinstitute.dsde.workbench.leonardo.{ReleaseName, RemoteUserName, ServiceConfig}

case class GalaxyAppConfig(releaseName: ReleaseName,
                           namespaceNameSuffix: NamespaceName,
                           services: List[ServiceConfig],
                           remoteUserName: RemoteUserName)
