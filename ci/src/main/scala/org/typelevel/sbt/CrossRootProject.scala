/*
 * Copyright 2022 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.typelevel.sbt

import sbt._
import org.typelevel.sbt.gha.GenerativePlugin.autoImport._

/**
 * Simultaneously creates a root project, a Scala JVM aggregate project,
 * a Scala.js aggregate project, a Scala Native aggregate project and
 * automatically enables the `NoPublishPlugin`.
 */
final class CrossRootProject private (
    val all: Project,
    val jvm: Project,
    val js: Project,
    val native: Project
) extends CompositeProject {

  override def componentProjects: Seq[Project] = Seq(all, jvm, js, native)

  def settings(ss: Def.SettingsDefinition*): CrossRootProject =
    new CrossRootProject(
      all.settings(ss: _*),
      jvm.settings(ss: _*),
      js.settings(ss: _*),
      native.settings(ss: _*)
    )

  def configure(transforms: (Project => Project)*): CrossRootProject =
    new CrossRootProject(
      all.configure(transforms: _*),
      jvm.configure(transforms: _*),
      js.configure(transforms: _*),
      native.configure(transforms: _*)
    )

  def enablePlugins(ns: Plugins*): CrossRootProject =
    new CrossRootProject(
      all.enablePlugins(ns: _*),
      jvm.enablePlugins(ns: _*),
      js.enablePlugins(ns: _*),
      native.enablePlugins(ns: _*)
    )

  def disablePlugins(ps: AutoPlugin*): CrossRootProject =
    new CrossRootProject(
      all.disablePlugins(ps: _*),
      jvm.disablePlugins(ps: _*),
      js.disablePlugins(ps: _*),
      native.disablePlugins(ps: _*)
    )

  def aggregate(projects: CompositeProject*): CrossRootProject =
    aggregateImpl(projects.flatMap(_.componentProjects): _*)

  private def aggregateImpl(projects: Project*): CrossRootProject = {
    val jsProjects =
      projects.filter(_.plugins.toString.contains("org.scalajs.sbtplugin.ScalaJSPlugin"))

    val nativeProjects =
      projects.filter(
        _.plugins.toString.contains("scala.scalanative.sbtplugin.ScalaNativePlugin"))

    val jvmProjects = projects.diff(jsProjects).diff(nativeProjects)

    new CrossRootProject(
      all.aggregate(projects.map(_.project): _*),
      if (jvmProjects.nonEmpty)
        jvm.aggregate(jvmProjects.map(_.project): _*).enablePlugins(TypelevelCiJVMPlugin)
      else jvm,
      if (jsProjects.nonEmpty)
        js.aggregate(jsProjects.map(_.project): _*).enablePlugins(TypelevelCiJSPlugin)
      else js,
      if (nativeProjects.nonEmpty)
        native
          .aggregate(nativeProjects.map(_.project): _*)
          .enablePlugins(TypelevelCiNativePlugin)
      else native
    )
  }

}

object CrossRootProject {
  def unapply(rootProject: CrossRootProject): Some[(Project, Project, Project, Project)] =
    Some((rootProject.all, rootProject.jvm, rootProject.js, rootProject.native))

  def apply(id: String): CrossRootProject = new CrossRootProject(
    Project(id, file(".")),
    Project(s"${id}JVM", file(".jvm")),
    Project(s"${id}JS", file(".js")),
    Project(s"${id}Native", file(".native"))
  ).enablePlugins(NoPublishPlugin, TypelevelCiCrossPlugin)
}

/**
 * This plugin is used internally by CrossRootProject.
 */
object TypelevelCiCrossPlugin extends AutoPlugin {
  override def requires = TypelevelCiPlugin

  override def buildSettings = Seq(
    githubWorkflowBuildSbtStepPreamble ~= { s"project $${{ matrix.project }}" +: _ },
    githubWorkflowBuildMatrixAdditions += "project" -> Nil,
    githubWorkflowArtifactDownloadExtraKeys += "project"
  )
}

// The following plugins are used internally to support CrossRootProject.

object TypelevelCiJVMPlugin extends AutoPlugin {
  override def requires = TypelevelCiCrossPlugin

  override def buildSettings: Seq[Setting[_]] = Seq(
    githubWorkflowBuildMatrixAdditions := {
      val matrix = githubWorkflowBuildMatrixAdditions.value
      val rootProject = (LocalRootProject / Keys.thisProject).value
      matrix.updated("project", matrix("project") ::: s"${rootProject.id}JVM" :: Nil)
    }
  )
}

object TypelevelCiJSPlugin extends AutoPlugin {
  override def requires = TypelevelCiCrossPlugin

  override def buildSettings: Seq[Setting[_]] = Seq(
    githubWorkflowBuildMatrixAdditions := {
      val matrix = githubWorkflowBuildMatrixAdditions.value
      val rootProject = (LocalRootProject / Keys.thisProject).value
      matrix.updated("project", matrix("project") ::: s"${rootProject.id}JS" :: Nil)
    },
    githubWorkflowBuildMatrixExclusions ++= {
      val rootProject = (LocalRootProject / Keys.thisProject).value
      githubWorkflowJavaVersions
        .value
        .tail
        .map(java =>
          MatrixExclude(Map("project" -> s"${rootProject.id}JS", "java" -> java.render)))
    },
    githubWorkflowBuild := {
      val steps = githubWorkflowBuild.value
      val rootProject = (LocalRootProject / Keys.thisProject).value
      steps.flatMap {
        case testStep @ WorkflowStep.Sbt(List("test"), _, _, _, _, _) =>
          val fastOptStep = WorkflowStep.Sbt(
            List("Test/scalaJSLinkerResult"),
            name = Some("scalaJSLink"),
            cond = Some(s"matrix.project == '${rootProject.id}JS'")
          )
          List(fastOptStep, testStep)
        case step => List(step)
      }
    }
  )

}

object TypelevelCiNativePlugin extends AutoPlugin {
  override def requires = TypelevelCiCrossPlugin

  override def buildSettings: Seq[Setting[_]] = Seq(
    githubWorkflowBuildMatrixAdditions := {
      val matrix = githubWorkflowBuildMatrixAdditions.value
      val rootProject = (LocalRootProject / Keys.thisProject).value
      matrix.updated("project", matrix("project") ::: s"${rootProject.id}Native" :: Nil)
    },
    githubWorkflowBuildMatrixExclusions ++= {
      val rootProject = (LocalRootProject / Keys.thisProject).value
      githubWorkflowJavaVersions
        .value
        .tail
        .map(java =>
          MatrixExclude(Map("project" -> s"${rootProject.id}Native", "java" -> java.render)))
    },
    githubWorkflowBuild := {
      val steps = githubWorkflowBuild.value
      val rootProject = (LocalRootProject / Keys.thisProject).value
      steps.flatMap {
        case testStep @ WorkflowStep.Sbt(List("test"), _, _, _, _, _) =>
          val nativeLinkStep = WorkflowStep.Sbt(
            List("Test/nativeLink"),
            name = Some("nativeLink"),
            cond = Some(s"matrix.project == '${rootProject.id}Native'")
          )
          List(nativeLinkStep, testStep)
        case step => List(step)
      }
    }
  )
}
