/*
    This file is part of OneModel, a program to manage knowledge.
    Copyright in each year of 2016-2017 inclusive, Luke A. Call; all rights reserved.
    OneModel is free software, distributed under a license that includes honesty, the Golden Rule, guidelines around binary
    distribution, and the GNU Affero General Public License as published by the Free Software Foundation;
    see the file LICENSE for license version and details.
    OneModel is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more details.
    You should have received a copy of the GNU Affero General Public License along with OneModel.  If not, see <http://www.gnu.org/licenses/>
*/
val projectVersion = "0.2.0-SNAPSHOT"

lazy val root = (project in file(".")).
  settings(
    organization := "org.onemodel",
    name := "om-web",
    version := projectVersion,
    scalaVersion := "2.11.8",
    resolvers += Resolver.mavenLocal,
    libraryDependencies += "org.onemodel" % "core" % projectVersion,
    libraryDependencies += "org.scalatestplus.play" % "scalatestplus-play_2.11" % "1.5.1" % "test",
    libraryDependencies += "commons-io" % "commons-io" % "2.5",
    libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.5"
  ).
  enablePlugins(PlayScala)

// cached resolution for performance on multiple subprojects. Docs said
// is experimental.  If issues, remove, and/or ck http://www.scala-sbt.org/1.0/docs/Cached-Resolution.html .
//updateOptions := updateOptions.value.withCachedResolution(true)
