package org.broadinstitute.dsde.workbench.leonardo
package http
package service

import akka.http.scaladsl.model.StatusCodes
import cats.Parallel
import cats.effect.Async
import cats.effect.std.Queue
import cats.implicits._
import cats.mtl.Ask
import com.azure.resourcemanager.compute.models.VirtualMachineSizeTypes
import org.broadinstitute.dsde.workbench.google2.{DiskName, MachineTypeName, ZoneName}
import org.broadinstitute.dsde.workbench.leonardo.AsyncTaskProcessor.Task
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId.{
  PersistentDiskSamResourceId,
  RuntimeSamResourceId,
  WorkspaceResourceSamResourceId,
  WsmResourceSamResourceId
}
import org.broadinstitute.dsde.workbench.leonardo.config.PersistentDiskConfig
import org.broadinstitute.dsde.workbench.leonardo.dao._
import org.broadinstitute.dsde.workbench.leonardo.db._
import org.broadinstitute.dsde.workbench.leonardo.model._
import org.broadinstitute.dsde.workbench.leonardo.monitor.LeoPubsubMessage
import org.broadinstitute.dsde.workbench.leonardo.monitor.LeoPubsubMessage.{
  CreateAzureRuntimeMessage,
  DeleteAzureRuntimeMessage
}
import org.broadinstitute.dsde.workbench.leonardo.util.CreateAzureRuntimeParams
import org.broadinstitute.dsde.workbench.model.{TraceId, UserInfo, WorkbenchEmail}
import org.http4s.headers.Authorization

import java.time.Instant
import scala.concurrent.ExecutionContext

class AzureServiceInterp[F[_]: Parallel](config: RuntimeServiceConfig,
                                         authProvider: LeoAuthProvider[F],
                                         wsmDao: WsmDao[F],
                                         samDAO: SamDAO[F],
                                         asyncTasks: Queue[F, Task[F]],
                                         publisherQueue: Queue[F, LeoPubsubMessage])(
  implicit F: Async[F],
  dbReference: DbReference[F],
  ec: ExecutionContext
) extends AzureService[F] {
  override def createRuntime(userInfo: UserInfo,
                             runtimeName: RuntimeName,
                             workspaceId: WorkspaceId,
                             req: CreateAzureRuntimeRequest,
                             createVmJobId: WsmJobId)(implicit as: Ask[F, AppContext]): F[Unit] =
    for {
      ctx <- as.ask

      leoAuth <- samDAO.getLeoAuthToken

      azureContext <- wsmDao.getWorkspace(workspaceId, leoAuth).map(_.azureContext)
      cloudContext = CloudContext.Azure(azureContext)

      samResource = WorkspaceResourceSamResourceId(workspaceId.value.toString)

      hasPermission <- authProvider.hasPermission(samResource, WorkspaceAction.CreateControlledUserResource, userInfo)

      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("Done auth call for azure runtime permission")))
      _ <- F
        .raiseUnless(hasPermission)(ForbiddenError(userInfo.userEmail))

      runtimeOpt <- RuntimeServiceDbQueries.getStatusByName(cloudContext, runtimeName).transaction
      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("Done DB query for azure runtime")))

      runtimeImage: RuntimeImage = RuntimeImage(
        RuntimeImageType.Azure,
        req.imageUri.map(_.value).getOrElse(config.azureConfig.runtimeConfig.imageUri.value),
        None,
        ctx.now
      )

      _ <- runtimeOpt match {
        case Some(status) => F.raiseError[Unit](RuntimeAlreadyExistsException(cloudContext, runtimeName, status))
        case None =>
          for {
            diskToSave <- F.fromEither(
              convertToDisk(
                userInfo,
                cloudContext,
                DiskName(req.azureDiskConfig.name.value),
                config.azureConfig.diskConfig,
                req,
                ctx.now
              )
            )

            runtime = convertToRuntime(runtimeName,
                                       cloudContext,
                                       userInfo,
                                       req,
                                       RuntimeSamResourceId(samResource.resourceId),
                                       Set(runtimeImage),
                                       ctx.now)

            disk <- persistentDiskQuery.save(diskToSave).transaction
            runtimeConfig = RuntimeConfig.AzureConfig(
              MachineTypeName(req.machineSize.toString),
              disk.id,
              req.region
            )
            runtimeToSave = SaveCluster(
              cluster = runtime,
              runtimeConfig = runtimeConfig,
              now = ctx.now,
              workspaceId = Some(workspaceId)
            )
            savedRuntime <- clusterQuery.save(runtimeToSave).transaction

            task = Task(
              ctx.traceId,
              createRuntime(CreateAzureRuntimeParams(workspaceId, savedRuntime, runtimeConfig, disk, runtimeImage),
                            WsmJobControl(createVmJobId)),
              Some(errorHandler(savedRuntime.id, ctx)),
              ctx.now
            )
            _ <- asyncTasks.offer(task)
            _ <- publisherQueue.offer(
              CreateAzureRuntimeMessage(savedRuntime.id, workspaceId, createVmJobId, Some(ctx.traceId))
            )
          } yield ()
      }

    } yield ()

  override def getRuntime(userInfo: UserInfo, runtimeName: RuntimeName, workspaceId: WorkspaceId)(
    implicit as: Ask[F, AppContext]
  ): F[GetRuntimeResponse] =
    for {
      leoAuth <- samDAO.getLeoAuthToken
      context <- as.ask
      azureContext <- wsmDao.getWorkspace(workspaceId, leoAuth).map(_.azureContext)
      cloudContext = CloudContext.Azure(azureContext)

      runtime <- RuntimeServiceDbQueries.getRuntime(cloudContext, runtimeName).transaction

      // If user is creator of the runtime, they should definitely be able to see the runtime.
      hasPermission <- if (runtime.auditInfo.creator == userInfo.userEmail) F.pure(true)
      else
        checkSamPermission(cloudContext, runtime.id, runtimeName, userInfo).map(_._1)

      _ <- context.span.traverse(s => F.delay(s.addAnnotation("Done auth call for get azure runtime permission")))
      _ <- F
        .raiseError[Unit](
          RuntimeNotFoundException(cloudContext, runtimeName, "permission denied", Some(context.traceId))
        )
        .whenA(!hasPermission)

    } yield runtime

  override def updateRuntime(userInfo: UserInfo,
                             runtimeName: RuntimeName,
                             workspaceId: WorkspaceId,
                             req: UpdateAzureRuntimeRequest)(implicit as: Ask[F, AppContext]): F[Unit] =
    F.pure(AzureUnimplementedException("patch not implemented yet"))

  override def deleteRuntime(userInfo: UserInfo, runtimeName: RuntimeName, workspaceId: WorkspaceId)(
    implicit as: Ask[F, AppContext]
  ): F[Unit] =
    for {
      leoAuth <- samDAO.getLeoAuthToken
      context <- as.ask

      azureContext <- wsmDao.getWorkspace(workspaceId, leoAuth).map(_.azureContext)
      cloudContext = CloudContext.Azure(azureContext)

      runtime <- RuntimeServiceDbQueries.getRuntime(cloudContext, runtimeName).transaction

      _ <- F
        .raiseUnless(runtime.status.isDeletable)(
          RuntimeCannotBeDeletedException(cloudContext, runtime.clusterName, runtime.status)
        )

      diskId <- runtime.runtimeConfig match {
        case config: RuntimeConfig.AzureConfig => F.pure(config.persistentDiskId)
        case _ =>
          F.raiseError[DiskId](
            AzureRuntimeHasInvalidRuntimeConfig(cloudContext, runtime.clusterName, context.traceId)
          )
      }

      (hasPermission, wmsResourceId) <- checkSamPermission(cloudContext, runtime.id, runtimeName, userInfo)

      _ <- context.span.traverse(s => F.delay(s.addAnnotation("Done auth call for delete azure runtime permission")))
      _ <- F
        .raiseError[Unit](RuntimeNotFoundException(cloudContext, runtimeName, "permission denied"))
        .whenA(!hasPermission)

      _ <- clusterQuery.markPendingDeletion(runtime.id, context.now).transaction

      // For now, azure disk life cycle is tied to vm life cycle and incompatible with disk routes
      _ <- persistentDiskQuery.markPendingDeletion(diskId, context.now).transaction

      _ <- publisherQueue.offer(
        DeleteAzureRuntimeMessage(runtime.id, Some(diskId), workspaceId, wmsResourceId, Some(context.traceId))
      )
    } yield ()

  private[service] def convertToDisk(userInfo: UserInfo,
                                     cloudContext: CloudContext,
                                     diskName: DiskName,
                                     config: PersistentDiskConfig,
                                     req: CreateAzureRuntimeRequest,
                                     now: Instant): Either[Throwable, PersistentDisk] = {
    // create a LabelMap of default labels
    val defaultLabelMap: LabelMap =
      Map(
        "diskName" -> diskName.value,
        "cloudContext" -> cloudContext.asString,
        "creator" -> userInfo.userEmail.value
      )

    // combine default and given labels
    val allLabels = req.azureDiskConfig.labels ++ defaultLabelMap

    for {
      // check the labels do not contain forbidden keys
      labels <- if (allLabels.contains(includeDeletedKey))
        Left(IllegalLabelKeyException(includeDeletedKey))
      else
        Right(allLabels)
    } yield PersistentDisk(
      DiskId(0),
      cloudContext,
      ZoneName(req.region.toString),
      diskName,
      userInfo.userEmail,
      //TODO: WSM will populate this, we can update in backleo if its needed for anything
      PersistentDiskSamResourceId("fakeUUID"),
      DiskStatus.Creating,
      AuditInfo(userInfo.userEmail, now, None, now),
      req.azureDiskConfig.size.getOrElse(config.defaultDiskSizeGb),
      req.azureDiskConfig.diskType.getOrElse(config.defaultDiskType),
      config.defaultBlockSizeBytes,
      None,
      None,
      labels
    )
  }

  /** Creates an Azure VM but doesn't wait for its completion.
   * This includes creation of all child Azure resources (disk, network, ip), and assumes these are created synchronously
   * */
  private def createRuntime(params: CreateAzureRuntimeParams,
                            jobControl: WsmJobControl)(implicit ev: Ask[F, AppContext]): F[Unit] =
    for {
      auth <- samDAO.getLeoAuthToken

      createIpAction = createIp(params, auth, params.runtime.runtimeName.asString)
      createDiskAction = createDisk(params, auth)
      createNetworkAction = createNetwork(params, auth, params.runtime.runtimeName.asString)

      createVmRequest <- (createIpAction, createDiskAction, createNetworkAction).parMapN {
        (ipResp, diskResp, networkResp) =>
          val vmCommon = getCommonFields(ControlledResourceName(params.runtime.runtimeName.asString),
                                         config.azureRuntimeDefaults.vmControlledResourceDesc,
                                         params.runtime.auditInfo.creator)
          CreateVmRequest(
            params.workspaceId,
            vmCommon,
            CreateVmRequestData(
              params.runtime.runtimeName,
              params.runtimeConfig.region,
              VirtualMachineSizeTypes.fromString(params.runtimeConfig.machineType.value),
              params.vmImage,
              ipResp.resourceId,
              diskResp.resourceId,
              networkResp.resourceId
            ),
            jobControl
          )
      }
      _ <- wsmDao.createVm(createVmRequest, auth)
    } yield ()

  private def createIp(params: CreateAzureRuntimeParams, leoAuth: Authorization, nameSuffix: String)(
    implicit ev: Ask[F, AppContext]
  ): F[CreateIpResponse] = {
    val common = getCommonFields(ControlledResourceName(s"ip-${nameSuffix}"),
                                 config.azureRuntimeDefaults.ipControlledResourceDesc,
                                 params.runtime.auditInfo.creator)

    val request: CreateIpRequest = CreateIpRequest(
      params.workspaceId,
      common,
      CreateIpRequestData(
        AzureIpName(s"ip-${nameSuffix}"),
        params.runtimeConfig.region
      )
    )
    for {
      ipResp <- wsmDao.createIp(request, leoAuth)
      _ <- controlledResourceQuery.save(params.runtime.id, ipResp.resourceId, WsmResourceType.AzureIp).transaction
    } yield ipResp
  }

  private def createDisk(params: CreateAzureRuntimeParams, leoAuth: Authorization)(
    implicit ev: Ask[F, AppContext]
  ): F[CreateDiskResponse] = {
    val common = getCommonFields(ControlledResourceName(params.disk.name.value),
                                 config.azureRuntimeDefaults.diskControlledResourceDesc,
                                 params.runtime.auditInfo.creator)
    val request: CreateDiskRequest = CreateDiskRequest(
      params.workspaceId,
      common,
      CreateDiskRequestData(
        //TODO: AzureDiskName should go away once DiskName is no longer coupled to google2 disk service
        AzureDiskName(params.disk.name.value),
        params.disk.size,
        params.runtimeConfig.region
      )
    )
    for {
      ctx <- ev.ask
      diskResp <- wsmDao.createDisk(request, leoAuth)
      _ <- controlledResourceQuery
        .save(params.runtime.id, diskResp.resourceId, WsmResourceType.AzureDisk)
        .transaction
      _ <- persistentDiskQuery.updateStatus(params.disk.id, DiskStatus.Ready, ctx.now).transaction
    } yield diskResp
  }

  private def createNetwork(
    params: CreateAzureRuntimeParams,
    leoAuth: Authorization,
    nameSuffix: String
  )(implicit ev: Ask[F, AppContext]): F[CreateNetworkResponse] = {
    val common = getCommonFields(ControlledResourceName(s"network-${nameSuffix}"),
                                 config.azureRuntimeDefaults.networkControlledResourceDesc,
                                 params.runtime.auditInfo.creator)
    val request: CreateNetworkRequest = CreateNetworkRequest(
      params.workspaceId,
      common,
      CreateNetworkRequestData(
        AzureNetworkName(s"vNet-${nameSuffix}"),
        AzureSubnetName(s"subnet-${nameSuffix}"),
        config.azureRuntimeDefaults.addressSpaceCidr,
        config.azureRuntimeDefaults.subnetAddressCidr,
        params.runtimeConfig.region
      )
    )
    for {
      networkResp <- wsmDao.createNetwork(request, leoAuth)
      _ <- controlledResourceQuery
        .save(params.runtime.id, networkResp.resourceId, WsmResourceType.AzureNetwork)
        .transaction
    } yield networkResp
  }

  private def getCommonFields(name: ControlledResourceName, resourceDesc: String, userEmail: WorkbenchEmail) =
    ControlledResourceCommonFields(
      name,
      ControlledResourceDescription(resourceDesc),
      CloningInstructions.Nothing, //TODO: these resources will not be cloned with clone-workspace. Is this correct?
      AccessScope.PrivateAccess,
      ManagedBy.Application,
      Some(
        PrivateResourceUser(
          userEmail,
          List(ControlledResourceIamRole.Writer)
        )
      )
    )

  private def checkSamPermission(cloudContext: CloudContext.Azure,
                                 runtimeId: Long,
                                 runtimeName: RuntimeName,
                                 userInfo: UserInfo)(
    implicit ctx: Ask[F, AppContext]
  ): F[(Boolean, WsmControlledResourceId)] =
    for {
      context <- ctx.ask
      controlledResourceOpt <- controlledResourceQuery
        .getWsmRecordForRuntime(runtimeId, WsmResourceType.AzureVm)
        .transaction
      azureRuntimeControlledResource <- F.fromOption(
        controlledResourceOpt,
        AzureRuntimeControlledResourceNotFoundException(cloudContext, runtimeName, context.traceId)
      )
      res <- authProvider.hasPermission(
        WsmResourceSamResourceId(azureRuntimeControlledResource.resourceId),
        WsmResourceAction.Read,
        userInfo
      )
    } yield (res, azureRuntimeControlledResource.resourceId)

  private def errorHandler(runtimeId: Long, ctx: AppContext): Throwable => F[Unit] =
    e =>
      clusterErrorQuery
        .save(runtimeId, RuntimeError(e.getMessage, None, ctx.now, Some(ctx.traceId)))
        .transaction >>
        clusterQuery.updateClusterStatus(runtimeId, RuntimeStatus.Error, ctx.now).transaction.void

  private def convertToRuntime(runtimeName: RuntimeName,
                               cloudContext: CloudContext,
                               userInfo: UserInfo,
                               request: CreateAzureRuntimeRequest,
                               samResourceId: RuntimeSamResourceId,
                               runtimeImages: Set[RuntimeImage],
                               now: Instant): Runtime = {
    // create a LabelMap of default labels
    val defaultLabels = DefaultRuntimeLabels(
      runtimeName,
      None,
      cloudContext,
      userInfo.userEmail,
      //TODO: use an azure service account
      Some(userInfo.userEmail),
      None,
      None,
      Some(RuntimeImageType.Azure)
    ).toMap

    val allLabels = request.labels ++ defaultLabels

    Runtime(
      0,
      samResource = samResourceId,
      runtimeName = runtimeName,
      cloudContext = cloudContext,
      //TODO: use an azure service account
      serviceAccount = userInfo.userEmail,
      asyncRuntimeFields = None,
      auditInfo = AuditInfo(userInfo.userEmail, now, None, now),
      kernelFoundBusyDate = None,
      proxyUrl = Runtime.getProxyUrl(config.proxyUrlBase, cloudContext, runtimeName, runtimeImages, allLabels),
      status = RuntimeStatus.PreCreating,
      labels = allLabels,
      userScriptUri = None,
      startUserScriptUri = None,
      errors = List.empty,
      userJupyterExtensionConfig = None,
      autopauseThreshold = 30,
      defaultClientId = None,
      allowStop = false,
      runtimeImages = runtimeImages,
      scopes = config.azureConfig.runtimeConfig.defaultScopes,
      welderEnabled = true,
      customEnvironmentVariables = request.customEnvironmentVariables,
      runtimeConfigId = RuntimeConfigId(-1),
      patchInProgress = false
    )
  }

}

final case class AzureRuntimeControlledResourceNotFoundException(cloudContext: CloudContext,
                                                                 runtimeName: RuntimeName,
                                                                 traceId: TraceId)
    extends LeoException(
      s"Controlled resource record not found for runtime ${cloudContext.asStringWithProvider}/${runtimeName.asString}",
      StatusCodes.NotFound,
      traceId = Some(traceId)
    )

final case class AzureRuntimeHasInvalidRuntimeConfig(cloudContext: CloudContext,
                                                     runtimeName: RuntimeName,
                                                     traceId: TraceId)
    extends LeoException(
      s"Azure runtime ${cloudContext.asStringWithProvider}/${runtimeName.asString} was found with an invalid runtime config",
      StatusCodes.InternalServerError,
      traceId = Some(traceId)
    )