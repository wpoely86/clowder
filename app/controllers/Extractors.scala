/**
 *
 */
package controllers

import play.api.mvc.Controller
import models.Extraction
import play.api.mvc.WebSocket
import play.api.libs.iteratee.Iteratee
import play.api.libs.iteratee.Enumerator

import play.api.mvc.Action

/**
 * Information about extractors.
 * 
 * @author Luigi Marini
 *
 */
object Extractors extends Controller with securesocial.core.SecureSocial {
//SecuredAction(ajaxCall = false)
  def extractions = Action { implicit request =>
    Ok(views.html.extractions(Extraction.findAll.toList))
  }
  
}