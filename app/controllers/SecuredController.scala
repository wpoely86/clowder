package controllers

import api.Permission.Permission
import api.{Permission, UserRequest}
import models.ResourceRef
import play.api.mvc._
import securesocial.core.{Authenticator, SecureSocial, UserService}
import services._
import scala.concurrent.Future

/**
 * Action builders check permissions in controller calls. When creating a new endpoint, pick one of the actions defined below.
 *
 * All functions will always resolve the usr and place the user in the request.user.
 *
 * UserAction: call the wrapped code, no checks are done
 * AuthenticatedAction: call the wrapped code iff the user is logged in.
 * ServerAdminAction: call the wrapped code iff the user is a server admin.
 * PermissionAction: call the wrapped code iff the user has the right permission on the reference object.
 *
 */
trait SecuredController extends Controller {
  /** get user if logged in */
  def UserAction = new ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[SimpleResult]) = {
      val userRequest = getUser(request)
      block(userRequest)
    }
  }
  /**
   * Use when you want to require the user to be logged in on a private server or the server is public.
   */
  def PrivateServerAction = new ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[SimpleResult]) = {
      val userRequest = getUser(request)
      if (Permission.checkPrivateServer(userRequest.user) || userRequest.superAdmin) {
        block(userRequest)
      } else {
        Future.successful(Results.Redirect(securesocial.controllers.routes.LoginPage.login)
          .flashing("error" -> "You must be logged in to access this page."))
      }
    }
  }

  /** call code iff user is logged in */
  def AuthenticatedAction = new ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[SimpleResult]) = {
      val userRequest = getUser(request)
      if (userRequest.user.isDefined || userRequest.superAdmin) {
        block(userRequest)
      } else {
        Future.successful(Results.Redirect(securesocial.controllers.routes.LoginPage.login)
          .flashing("error" -> "You must be logged in to access this page."))
      }
    }
  }

  /** call code iff user is a server admin */
  def ServerAdminAction = new ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[SimpleResult]) = {
      val userRequest = getUser(request)
      if (Permission.checkServerAdmin(userRequest.user) || userRequest.superAdmin) {
        block(userRequest)
      } else {
        Future.successful(Results.Redirect(securesocial.controllers.routes.LoginPage.login)
          .flashing("error" -> "You must be logged in as an administrator to access this page."))
      }
    }
  }

  /** call code iff user has right permission for resource */
  def PermissionAction(permission: Permission, resourceRef: Option[ResourceRef] = None) = new ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[SimpleResult]) = {
      val userRequest = getUser(request)
      val p = Permission.checkPermission(userRequest.user, permission, resourceRef)
      if (p || userRequest.superAdmin) {
        block(userRequest)
      } else {
        lazy val space: SpaceService = DI.injector.getInstance(classOf[SpaceService])
        lazy val collection: CollectionService = DI.injector.getInstance(classOf[CollectionService])
        lazy val dataset: DatasetService = DI.injector.getInstance(classOf[DatasetService])
        lazy val file: FileService = DI.injector.getInstance(classOf[FileService])
        val messgae = {
          resourceRef.get match {
            // TODO "Not authorized" occurs with other ResourceRef.Type or there is resourceRef.parse
            case ResourceRef(ResourceRef.file, id) => "file " + file.get(id).get.filename
            case ResourceRef(ResourceRef.dataset, id) => "dataset " + dataset.get(id).get.name
            case ResourceRef(ResourceRef.collection, id) => "collection " + collection.get(id).get.name
            case ResourceRef(ResourceRef.space, id) => "space " + space.get(id).get.name
            case ResourceRef(resType, id) => resType.toString() + " " + id
          }
        }
        Future.successful(Results.Redirect(routes.Authentication.notAuthorized(messgae)))
      }
    }
  }

  /**
   * Disable a route without having to comment out the entry in the routes file. Useful for when we want to keep the
   * code around but we don't want users to have access to it.
   */
  def DisabledAction = new ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[SimpleResult]) = {
      Future.successful(Results.Redirect(routes.Authentication.notAuthorized()))
    }
  }

  /** Return user based on request object */
  def getUser[A](request: Request[A]): UserRequest[A] = {
    // controllers will check for user in the following order:
    // 1) secure social
    // 2) anonymous access

    // 1) secure social, this allows the web app to make calls to the API and use the secure social user
    for (
      authenticator <- SecureSocial.authenticatorFromRequest(request);
      identity <- UserService.find(authenticator.identityId)
    ) yield {
      Authenticator.save(authenticator.touch)
      val user = DI.injector.getInstance(classOf[services.UserService]).findByIdentity(identity)
      return UserRequest(user, superAdmin=false, request)
    }

    // 2) anonymous access
    UserRequest(None, superAdmin=false, request)
  }
}
