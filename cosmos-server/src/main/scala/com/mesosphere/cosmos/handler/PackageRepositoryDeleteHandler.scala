package com.mesosphere.cosmos.handler

import cats.data.Ior
import com.mesosphere.cosmos.RepoNameOrUriMissing
import com.mesosphere.cosmos.http.{MediaType, MediaTypes, RequestSession}
import com.mesosphere.cosmos.model.{PackageRepositoryDeleteRequest, PackageRepositoryDeleteResponse}
import com.mesosphere.cosmos.repository.PackageSourcesStorage
import com.twitter.util.Future
import io.circe.Encoder
import io.finch.DecodeRequest

private[cosmos] final class PackageRepositoryDeleteHandler(sourcesStorage: PackageSourcesStorage)(
  implicit
  decoder: DecodeRequest[PackageRepositoryDeleteRequest],
  encoder: Encoder[PackageRepositoryDeleteResponse]
) extends EndpointHandler[PackageRepositoryDeleteRequest, PackageRepositoryDeleteResponse] {

  import PackageRepositoryDeleteHandler._

  override val accepts: MediaType = MediaTypes.PackageRepositoryDeleteRequest
  override val produces: MediaType = MediaTypes.PackageRepositoryDeleteResponse

  override def apply(
    request: PackageRepositoryDeleteRequest
  )(implicit session: RequestSession): Future[PackageRepositoryDeleteResponse] = {
    val nameOrUri = optionsToIor(request.name, request.uri).getOrElse(throw RepoNameOrUriMissing())
    sourcesStorage.delete(nameOrUri).map { sources =>
      PackageRepositoryDeleteResponse(sources)
    }
  }
}

private object PackageRepositoryDeleteHandler {

  private def optionsToIor[A, B](aOpt: Option[A], bOpt: Option[B]): Option[Ior[A, B]] = {
    (aOpt, bOpt) match {
      case (Some(a), Some(b)) => Some(Ior.Both(a, b))
      case (Some(a), _) => Some(Ior.Left(a))
      case (_, Some(b)) => Some(Ior.Right(b))
      case _ => None
    }
  }

}
