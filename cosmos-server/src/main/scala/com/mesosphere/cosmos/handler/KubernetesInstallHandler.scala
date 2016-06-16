package com.mesosphere.cosmos.handler

import java.io.{StringReader, StringWriter}
import java.util.Base64
import scala.collection.JavaConverters._
import cats.data.Xor
import com.github.mustachejava.DefaultMustacheFactory
import com.twitter.io.Charsets
import com.twitter.util.Future
import io.circe.parse.parse
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}
import io.finch.DecodeRequest
import com.mesosphere.cosmos.http.{MediaTypes, RequestSession}
import com.mesosphere.cosmos.jsonschema.JsonSchemaValidation
import com.mesosphere.cosmos.model._
import com.mesosphere.cosmos.model.thirdparty.kubernetes.KubernetesPod
import com.mesosphere.cosmos.repository.PackageCollection
import com.mesosphere.cosmos.{CirceError, JsonSchemaMismatch, PackageFileNotJson, PackageRunner}
import com.mesosphere.universe.{PackageFiles, Resource}

private[cosmos] final class KubernetesInstallHandler(
  packageCache: PackageCollection,
  packageRunner: PackageRunner
)(implicit
  bodyDecoder: DecodeRequest[InstallKubernetesRequest],
  encoder: Encoder[InstallKubernetesResponse]
) extends EndpointHandler[InstallKubernetesRequest, InstallKubernetesResponse] {

  val accepts = MediaTypes.InstallKubernetesRequest
  val produces = MediaTypes.InstallKubernetesResponse

  import KubernetesInstallHandler._

  override def apply(request: InstallKubernetesRequest)(implicit session: RequestSession): Future[InstallKubernetesResponse] = {
    packageCache
      .getPackageByPackageVersion(request.packageName, request.packageVersion)
      .flatMap { packageFiles =>
        val packageConfig = preparePackageConfig(request.options, packageFiles)
        val logger = org.slf4j.LoggerFactory.getLogger(getClass)
        logger.info("The prepared package config was:{}", packageConfig)
        packageRunner
          .launch_1(packageConfig)
          .map { runnerResponse =>
            val packageName = packageFiles.packageJson.name
            val packageVersion = packageFiles.packageJson.version
            val kind = runnerResponse.kind
            val apiVersion = runnerResponse.apiVersion
//            val appId = runnerResponse.id
            InstallKubernetesResponse(packageName, packageVersion, kind, apiVersion)
          }
      }
  }
}

private[cosmos] object KubernetesInstallHandler {

  import com.mesosphere.cosmos.circe.Encoders._  //TODO: Not crazy about this being here
  private[this] val MustacheFactory = new DefaultMustacheFactory()

  private[cosmos] def preparePackageConfig(
//    appId: Option[AppId],
    options: Option[JsonObject],
    packageFiles: PackageFiles
  ): Json = {
    val logger = org.slf4j.LoggerFactory.getLogger(getClass)
    logger.info("the Kubernetes Files was: {}", packageFiles)
    logger.info("the Package options was: {}", options)
    val mergedOptions = mergeOptions(packageFiles, options)
    logger.info("the merged options was: {}", mergedOptions)
    logger.info("the Kubernetes Files was xxxxxxxxx: {}", packageFiles)

    renderMustacheTemplate(packageFiles, mergedOptions)
//    val marathonJson = renderMustacheTemplate(packageFiles, mergedOptions)
//    val marathonJsonWithLabels = addLabels(marathonJson, packageFiles, mergedOptions)

//    appId match {
//     case Some(id) => marathonJsonWithLabels.mapObject(_ + ("id", id.asJson))
//      case _ => marathonJsonWithLabels
//    }
   }

  private[this] def validConfig(options: JsonObject, config: JsonObject): JsonObject = {
    val validationErrors = JsonSchemaValidation.matchesSchema(options, config)
    if (validationErrors.nonEmpty) {
      throw JsonSchemaMismatch(validationErrors)
    }
    options
  }

  private[this] def mergeOptions(
    packageFiles: PackageFiles,
    options: Option[JsonObject]
  ): Json = {
    val logger = org.slf4j.LoggerFactory.getLogger(getClass)
    logger.info("now in mergeOptions")
    logger.info("The package file in mergeOptions was: {}", packageFiles)

    val defaults = extractDefaultsFromConfig(packageFiles.configJson)

    logger.info("The defaults variable was: {}", defaults)
    logger.info("The package fils was: {} ", packageFiles)
    logger.info("The package file configjson was: {}", packageFiles.configJson)

    val merged: JsonObject = (packageFiles.configJson, options) match {
      case (None, None) => JsonObject.empty
      case (Some(config), None) => validConfig(defaults, config)
      case (None, Some(_)) =>
        val error = Map("message" -> "No schema available to validate the provided options").asJson
        throw JsonSchemaMismatch(List(error))
      case (Some(config), Some(opts)) =>
        val m = merge(defaults, opts)
        validConfig(m, config)
    }

    val resource = extractAssetsAsJson(packageFiles.resourceJson)
    val complete = merged + ("resource", Json.fromJsonObject(resource))
    Json.fromJsonObject(complete)
  }

  private[this] def renderMustacheTemplate(
    packageFiles: PackageFiles,
    mergedOptions: Json
  ): Json = {
            val logger = org.slf4j.LoggerFactory.getLogger(getClass)

    val strReader = new StringReader(packageFiles.marathonJsonMustache)
    logger.info("Marathon package file: {}", packageFiles)
    logger.info("marathon package file: {}", packageFiles.marathonJsonMustache)
    logger.info("strReader was: {}", strReader)
    val mustache = MustacheFactory.compile(strReader, "marathon.json.mustache")
    logger.info("mustache was: {}", mustache)

    val params = jsonToJava(mergedOptions)
    logger.info("params was: {}", params)

    val output = new StringWriter()
    mustache.execute(output, params)
    parse(output.toString) match {
      case Xor.Left(err) => throw PackageFileNotJson("marathon.json", err.message)
      case Xor.Right(rendered) => rendered
    }
  }

  private[this] def extractAssetsAsJson(resource: Option[Resource]): JsonObject = {
    val assets = resource.map(_.assets) match {
      case Some(a) => a.asJson
      case _ => Json.obj()
    }

    JsonObject.singleton("assets", assets)
  }

  private[this] def extractDefaultsFromConfig(configJson: Option[JsonObject]): JsonObject = {
    configJson
      .flatMap { json =>
        val topProperties =
          json("properties")
            .getOrElse(Json.empty)

        filterDefaults(topProperties)
          .asObject
      }
      .getOrElse(JsonObject.empty)
  }

  private[this] def filterDefaults(properties: Json): Json = {
    val defaults = properties
      .asObject
      .getOrElse(JsonObject.empty)
      .toMap
      .flatMap { case (propertyName, propertyJson) =>
        propertyJson
          .asObject
          .flatMap { propertyObject =>
            propertyObject("default").orElse {
              propertyObject("properties").map(filterDefaults)
            }
          }
          .map(propertyName -> _)
      }

    Json.fromJsonObject(JsonObject.fromMap(defaults))
  }

  private[this] def jsonToJava(json: Json): Any = {
    json.fold(
      jsonNull = null,
      jsonBoolean = identity,
      jsonNumber = n => n.toInt.getOrElse(n.toDouble),
      jsonString = identity,
      jsonArray = _.map(jsonToJava).asJava,
      jsonObject = _.toMap.mapValues(jsonToJava).asJava
    )
  }

//  private[this] def addLabels(
//    marathonJson: Json,
//    packageFiles: PackageFiles,
//    mergedOptions: Json
//  ): Json = {

//   val packageMetadataJson = getPackageMetadataJson(packageFiles)

//    val packageMetadata = encodeForLabel(packageMetadataJson)

//    val commandMetadata = packageFiles.commandJson.map { commandJson =>
//      val bytes = commandJson.asJson.noSpaces.getBytes(Charsets.Utf8)
//      Base64.getEncoder.encodeToString(bytes)
//    }

//   val isFramework = packageFiles.packageJson.framework.getOrElse(true)

/*    val requiredLabels: Map[String, String] = Map(
      (MarathonApp.metadataLabel, packageMetadata),
      (MarathonApp.registryVersionLabel, packageFiles.packageJson.packagingVersion.toString),
      (MarathonApp.nameLabel, packageFiles.packageJson.name),
      (MarathonApp.versionLabel, packageFiles.packageJson.version.toString),
      (MarathonApp.repositoryLabel, packageFiles.sourceUri.toString),
      (MarathonApp.releaseLabel, packageFiles.revision),
      (MarathonApp.isFrameworkLabel, isFramework.toString)
    )
*/
//    val nonOverridableLabels: Map[String, String] = Seq(
//      commandMetadata.map(MarathonApp.commandLabel -> _)
//    ).flatten.toMap

//    val hasLabels = marathonJson.cursor.fieldSet.exists(_.contains("labels"))
//    val existingLabels = if (hasLabels) {
//       marathonJson.cursor.get[Map[String, String]]("labels") match {
//          case Xor.Left(df) => throw CirceError(df)
//          case Xor.Right(labels) => labels
//        }
//    } else {
//      Map.empty[String, String]
//    }

//    val packageLabels = requiredLabels ++ existingLabels ++ nonOverridableLabels
//    val packageLabels = existingLabels ++ nonOverridableLabels

//    marathonJson.mapObject(_ + ("labels", packageLabels.asJson))
//  }

/*  private[this] def getPackageMetadataJson(packageFiles: PackageFiles): Json = {
    val packageJson = packageFiles.packageJson.asJson

    // add images to package.json metadata for backwards compatability in the UI
    val imagesJson = packageFiles.resourceJson.map(_.images.asJson)
    val packageWithImages = imagesJson match {
      case Some(images) =>
        packageJson.mapObject(_ + ("images", images))
      case None =>
        packageJson
    }

    removeNulls(packageWithImages)
  }
*/

  /** Circe populates omitted fields with null values; remove them (see GitHub issue #56) */
//  private[this] def removeNulls(json: Json): Json = {
//    json.mapObject { obj =>
//      JsonObject.fromMap(obj.toMap.filterNot { case (k, v) => v.isNull })
//    }
//  }

//  private[this] def encodeForLabel(json: Json): String = {
//    val bytes = json.noSpaces.getBytes(Charsets.Utf8)
//    Base64.getEncoder.encodeToString(bytes)
//  }

  private[cosmos] def merge(target: JsonObject, fragment: JsonObject): JsonObject = {
    fragment.toList.foldLeft(target) { (updatedTarget, fragmentEntry) =>
      val (fragmentKey, fragmentValue) = fragmentEntry
      val targetValueOpt = updatedTarget(fragmentKey)

      val mergedValue = (targetValueOpt.flatMap(_.asObject), fragmentValue.asObject) match {
        case (Some(targetObject), Some(fragmentObject)) =>
          Json.fromJsonObject(merge(targetObject, fragmentObject))
        case _ => fragmentValue
      }

      updatedTarget + (fragmentKey, mergedValue)
    }
  }

}