package actors

import akka.actor._
import play.api.libs.json._
import play.api.mvc.RequestHeader

import json._

import models.PLM
import log.PLMLogger
import spies._
import plm.core.model.lesson.Exercise
import plm.core.model.lesson.Lesson
import plm.core.model.lesson.Lecture
import plm.core.lang.ProgrammingLanguage
import plm.universe.Entity
import plm.universe.World
import plm.universe.IWorldView
import plm.universe.GridWorld
import plm.universe.GridWorldCell
import plm.universe.bugglequest.BuggleWorld
import plm.universe.bugglequest.AbstractBuggle
import plm.universe.bugglequest.BuggleWorldCell

import play.api.Play.current
import play.api.i18n.Lang
import play.api.Logger

object PLMActor {
  def props(out: ActorRef, preferredLang: Lang) = Props(new PLMActor(out, preferredLang))
}

class PLMActor(out: ActorRef, preferredLang: Lang) extends Actor {
  var availableLangs = Lang.availables
  var isProgressSpyAdded: Boolean = false
  var plmLogger = new PLMLogger(this)
  var plm = new PLM(plmLogger, preferredLang.toLocale)
  
  var resultSpy: ExecutionResultListener = new ExecutionResultListener(this, plm.game)
  plm.game.addGameStateListener(resultSpy)
  
  var progLangSpy: ProgLangListener = new ProgLangListener(this, plm)
  plm.game.addProgLangListener(progLangSpy, true)
  
  var humanLangSpy: HumanLangListener = new HumanLangListener(this, plm)
  plm.game.addHumanLangListener(humanLangSpy, true)
  
  var registeredSpies: List[ExecutionSpy] = List()
  
  def receive = {
    case msg: JsValue =>
      Logger.debug("Received a message")
      Logger.debug(msg.toString())
      var cmd: Option[String] = (msg \ "cmd").asOpt[String]
      cmd.getOrElse(None) match {
        case "getLessons" =>
          sendMessage("lessons", Json.obj(
            "lessons" -> LessonToJson.lessonsWrite(plm.lessons)
          ))
        case "setProgrammingLanguage" =>
          var optProgrammingLanguage: Option[String] = (msg \ "args" \ "programmingLanguage").asOpt[String]
          (optProgrammingLanguage.getOrElse(None)) match {
            case programmingLanguage: String =>
              plm.setProgrammingLanguage(programmingLanguage)
            case _ =>
              Logger.debug("getExercise: non-correct JSON")
          }
        case "setLang" =>
          var optLang: Option[String] =  (msg \ "args" \ "lang").asOpt[String]
          (optLang.getOrElse(None)) match {
            case lang: String =>
              plm.setLang(Lang(lang))
            case _ =>
              Logger.debug("getExercise: non-correct JSON")
          }
        case "filterMission" =>
            var optMissionText: Option[String] = (msg \ "args" \ "missionText").asOpt[String]
            var optAll: Option[Boolean] = (msg \ "args" \ "all").asOpt[Boolean]
            var optLangs: Option[Array[String]] = (msg \ "args" \ "languages").asOpt[Array[String]]

            (optMissionText.getOrElse(None), optAll.getOrElse(None), optLangs.getOrElse(None)) match {
                case (missionText: String, all: Boolean, langs: Array[String]) => {
                    if(all) {
                        sendMessage("missionFiltered", Json.obj("filteredMission" -> plm.filterMission(missionText, true, false, null)))
                    }
                    else {
                        sendMessage("missionFiltered", Json.obj("filteredMission" -> plm.filterMission(missionText, false, true, langs)))
                    }
                }
                case _ => Logger.debug("filterMission: non-correct JSON")
            }
            
        case "getExercise" =>
          var optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          var optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]
          var lecture: Lecture = null;
          var executionSpy: ExecutionSpy = new ExecutionSpy(this, "operations")
          var demoExecutionSpy: ExecutionSpy = new ExecutionSpy(this, "demoOperations")
          (optLessonID.getOrElse(None), optExerciseID.getOrElse(None)) match {
            case (lessonID:String, exerciseID: String) =>
              lecture = plm.switchExercise(lessonID, exerciseID, executionSpy, demoExecutionSpy)
            case (lessonID:String, _) =>
              lecture = plm.switchLesson(lessonID, executionSpy, demoExecutionSpy)
            case (_, _) =>
              Logger.debug("getExercise: non-correct JSON")
          }
          if(lecture != null) {
            sendMessage("exercise", Json.obj(
              "exercise" -> LectureToJson.lectureWrites(lecture, plm.programmingLanguage, plm.getStudentCode, plm.getInitialWorlds, plm.getSelectedWorldID)
            ))
          }
        case "runExercise" =>
          var optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          var optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]
          var optCode: Option[String] = (msg \ "args" \ "code").asOpt[String]
          (optLessonID.getOrElse(None), optExerciseID.getOrElse(None), optCode.getOrElse(None)) match {
            case (lessonID:String, exerciseID: String, code: String) =>
              plm.runExercise(lessonID, exerciseID, code)
            case (_, _, _) =>
              Logger.debug("runExercise: non-correctJSON")
          }
        case "runDemo" =>
          var optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          var optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]
          (optLessonID.getOrElse(None), optExerciseID.getOrElse(None)) match {
            case (lessonID:String, exerciseID: String) =>
              plm.runDemo(lessonID, exerciseID)
            case (_, _) =>
              Logger.debug("runDemo: non-correctJSON")
          }
        case "stopExecution" =>
          plm.stopExecution
        case "revertExercise" =>
          var lecture = plm.revertExercise
          sendMessage("exercise", Json.obj(
              "exercise" -> LectureToJson.lectureWrites(lecture, plm.programmingLanguage, plm.getStudentCode, plm.getInitialWorlds, plm.getSelectedWorldID)
          ))
        case "getExercises" =>
          var lectures = plm.game.getCurrentLesson.getRootLectures.toArray(Array[Lecture]())
          sendMessage("exercises", Json.obj(
            "exercises" -> ExerciseToJson.exercisesWrite(lectures) 
          ))
        case "getLangs" =>
          sendMessage("langs", Json.obj(
            "selected" -> LangToJson.langWrite(preferredLang),
            "availables" -> LangToJson.langsWrite(availableLangs)
          ))
        case _ =>
          Logger.debug("cmd: non-correct JSON")
      }
  }
  
  def createMessage(cmdName: String, mapArgs: JsValue): JsValue = {
    return Json.obj(
      "cmd" -> cmdName,
      "args" -> mapArgs
    )
  }
  
  def sendMessage(cmdName: String, mapArgs: JsValue) {
    out ! createMessage(cmdName, mapArgs)
  }
  
  def registerSpy(spy: ExecutionSpy) {
    registeredSpies = registeredSpies ::: List(spy)
  }
  
  override def postStop() = {
    Logger.debug("postStop: websocket closed - removing the spies")
    plm.game.removeGameStateListener(resultSpy)
    plm.game.removeProgLangListener(progLangSpy)
    registeredSpies.foreach { spy => spy.unregister }
  }
}