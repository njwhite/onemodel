/*  This file is part of OneModel, a program to manage knowledge.
    Copyright in each year of 2014-2017 inclusive, Luke A. Call; all rights reserved.
    OneModel is free software, distributed under a license that includes honesty, the Golden Rule, guidelines around binary
    distribution, and the GNU Affero General Public License as published by the Free Software Foundation;
    see the file LICENSE for license version and details.
    OneModel is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more details.
    You should have received a copy of the GNU Affero General Public License along with OneModel.  If not, see <http://www.gnu.org/licenses/>
*/
package org.onemodel.core

import java.io.File
import java.nio.file.{Files, Path}

import org.onemodel.core.controllers.{Controller, ImportExport}
import org.onemodel.core.model._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{Args, FlatSpec, Status}

import scala.collection.JavaConversions._
import scala.collection.mutable

/**
 * IT IS IMPORTANT THAT SUCH TESTS USE THE SAME DB variable INSIDE ANY ELEMENTS PASSED IN TO THE TEST METHOD, AS IN THE CONTROLLER SUBCLASS THAT IS BEING
 * TESTED!!, OTHERWISE YOU GET TWO DB CONNECTIONS, AND THEY CAN'T SEE EACH OTHERS' DATA BECAUSE IT'S INSIDE A TRANSACTION, AND IT'S A MESS.
 * Idea: Think more about cleaner design, for this?  Like, how to avoid the problems I had when accidentally just using mImportExport.mDB during tests,
 * which by default connects to the user db, not the test db!?
 */
class ImportExportTest extends FlatSpec with MockitoSugar {
  var mEntity: Entity = null

  // idea!!: instead of "new TextUI" pass in something that extends or implements a parent of them both, and does the right things for tests (like, maybe
  // answers everything in a particular way?):  but it shouldn't be used at all, anyway in this case).  When that is done, one can remove the "testing = true"
  // parameter below.
  val ui = new TextUI
  var mImportExport: ImportExport = null
  var mDB: PostgreSQLDatabase = null

  override def runTests(testName: Option[String], args: Args): Status = {
    setUp()
    val result: Status = super.runTests(testName, args)
    // (not calling tearDown: see comment inside PostgreSQLDatabaseTest.runTests about "db setup/teardown")
    result
  }

  protected def setUp() {
    //start fresh
    PostgreSQLDatabaseTest.tearDownTestDB()

    //// instantiation does DB setup (creates tables, default data, etc):
    mDB = new PostgreSQLDatabase(Database.TEST_USER, Database.TEST_USER)
    mDB.createRelationType("a test relation type","","UNI")
    // idea: fix the bad smell: shouldn't need a ui (& maybe not a controller?) to run tests of logic.  Noted in tasks to fix.
    //(ALSO FIX SIMILAR USAGE IN PostgreSQLDatabaseTest.)
    mImportExport = new ImportExport(ui, new Controller(ui, forceUserPassPromptIn = false,
                                                             defaultUsernameIn = Some(Database.TEST_USER), defaultPasswordIn = Some(Database.TEST_USER)))

    val entityId: Long = mDB.createEntity("test object")
    mEntity = new Entity(mDB, entityId)
  }

  protected def tearDown() {
    PostgreSQLDatabaseTest.tearDownTestDB()
  }

  def tryExportingHtml(ids: java.util.ArrayList[Long]): (String, Array[String]) = {
    assert(ids.size > 0)
    val entityId: Long = ids.get(0)
    val startingEntity: Entity = new Entity(mDB, entityId)

    // see comments in ImportExport.export() method for explanation of the next few lines:
    val exportedEntityIds = new mutable.TreeSet[String]
    val cachedEntities = new mutable.HashMap[String, Entity]
    val cachedAttrs = new mutable.HashMap[Long, Array[(Long, Attribute)]]
    val cachedGroupInfo = new mutable.HashMap[Long, Array[Long]]

    val prefix: String = mImportExport.getExportFileNamePrefix(startingEntity, ImportExport.HTML_EXPORT_TYPE)
    val outputDirectory: Path = mImportExport.createOutputDir("omtest-" + prefix)
    val uriClassId: Long = startingEntity.mDB.getOrCreateClassAndTemplateEntity("URI", callerManagesTransactionsIn = true)._1
    val quoteClassId = startingEntity.mDB.getOrCreateClassAndTemplateEntity("quote", callerManagesTransactionsIn = true)._1
    mImportExport.exportHtml(startingEntity, levelsToExportIsInfinite = true, 0, outputDirectory, exportedEntityIds, cachedEntities, cachedAttrs,
                             cachedGroupInfo, mutable.TreeSet[Long](), uriClassId, quoteClassId, includePublicData = true, includeNonPublicData = true,
                             includeUnspecifiedData = true, None, None, Some("2015 thisisatestpersonname"))

    assert(outputDirectory.toFile.exists)
    val newFiles: Array[String] = outputDirectory.toFile.list
    val firstNewFileName = "e" + entityId + ".html"
    val firstNewFile = new File(outputDirectory.toFile, firstNewFileName)
    val firstNewFileContents: String = new Predef.String(Files.readAllBytes(firstNewFile.toPath))
    assert(newFiles.contains(firstNewFileName), "unexpected filenames, like: " + newFiles(0))
    (firstNewFileContents, newFiles)
  }


  // This is because it's easy to break this UI feature of rolling back after an import that doesn't look desired, by adding
  // transaction logic in the db layer somewhere that the ImportExport code uses, and not realizing it. Another option would be
  // to have a callerManagesTransactions parameter *everywhere*?: ick.
  // Better yet, is there a way to tell in code if anything called between two lines tries to start or commit a transaction? (ie, "I want to control this, none else.")
  // Maybe this should really be in a db test class since db logic is what it's actually checking.
  "testImport" should "not persist if rollback attempted" in {
    mDB.beginTrans()
    mImportExport.tryImporting_FOR_TESTS("testImportFile0.txt", mEntity)
    mDB.rollbackTrans()
    assert(mDB.findAllEntityIdsByName("vsgeer-testing-getJournal-in-db").isEmpty)

    //check it again with data that has a text attribute, since it adds an operation to the import, and any such could have a transaction issue
    mDB.beginTrans()
    mImportExport.tryImporting_FOR_TESTS("testImportFile4.txt", mEntity)
    mDB.rollbackTrans()
    assert(mDB.findAllEntityIdsByName("vsgeer4").isEmpty)
  }

  "testImportAndExportOfSimpleTxt" should "work" in {
    val importFile: File = mImportExport.tryImporting_FOR_TESTS("testImportFile0.txt", mEntity)
    val ids: java.util.ArrayList[Long] = mDB.findAllEntityIdsByName("vsgeer-testing-getJournal-in-db")

    val (fileContents: String, outputFile: File) = mImportExport.tryExportingTxt_FOR_TESTS(ids, mDB)

    assert(fileContents.contains("vsgeer"), "unexpected file contents:  " + fileContents)
    assert(fileContents.contains("record/report/review"), "unexpected file contents:  " + fileContents)
    assert(outputFile.length == importFile.length)
  }

  "testImportBadTaFormat1" should "demonstrate throwing an exception" in {
    val name = "testImportBadTaFormat1"
    System.out.println("starting " + name)
    intercept[OmException] {
                             mImportExport.tryImporting_FOR_TESTS("testImportFile2.txt", mEntity)
                           }
  }

  "testImportBadTaFormat2" should "also demonstrate throwing an exception" in {
    val name = "testImportBadTaFormat2"
    System.out.println("starting " + name)
    intercept[OmException] {
                             mImportExport.tryImporting_FOR_TESTS("testImportFile3.txt", mEntity)
                           }
  }

  "testImportGoodTaFormat" should "demonstrate importing with content to become a TextAttribute, specifying a valid attribute type name" in {
    val name = "testImportGoodTaFormat"
    System.out.println("starting " + name)

    // no exceptions:
    mImportExport.tryImporting_FOR_TESTS("testImportFile4.txt", mEntity)

    // make sure it actually imported something expected:
    val ids: java.util.ArrayList[Long] = mDB.findAllEntityIdsByName("lastTopLevelLineIn-testImportFile4.txt")
    assert(ids.size > 0)
    var foundIt = false
    val relationTypeId = mDB.findRelationType(Database.theHASrelationTypeName, Some(1)).get(0)
    for (entityId: Long <- ids) {
      // (could have used mDB.getContainingEntities1 here perhaps)
      if (mDB.relationToLocalEntityExists(relationTypeId, mEntity.getId, entityId)) {
        foundIt = true
      }
    }
    assert(foundIt)
  }

  "testExportHtml" should "work" in {
    mImportExport.tryImporting_FOR_TESTS("testImportFile4.txt", mEntity)
    val ids: java.util.ArrayList[Long] = mDB.findAllEntityIdsByName("vsgeer4")
    val (firstNewFileContents: String, newFiles: Array[String]) = tryExportingHtml(ids)

    assert(firstNewFileContents.contains("<a href=\"e-"), "unexpected file contents: no href?:  " + firstNewFileContents)
    assert(firstNewFileContents.contains("purpose"), "unexpected file contents: no 'purpose'?:  " + firstNewFileContents)
    assert(firstNewFileContents.contains(".html\">empowerment</a>"), "unexpected file contents: no 'empowerment'?:  " + firstNewFileContents)
    assert(firstNewFileContents.contains("Copyright"), "unexpected file contents: no copyright?")
    assert(firstNewFileContents.contains("all rights reserved"), "unexpected file contents: no 'all rights reserved' from the input file?")
    assert(newFiles.length > 5, "unexpected # of files: " + newFiles.length)
  }

  "testImportAndExportOfUri" should "work" in {
    mImportExport.tryImporting_FOR_TESTS("testImportFile5.txt", mEntity)
    val ids: java.util.ArrayList[Long] = mDB.findAllEntityIdsByName("import-file-5")
    val firstNewFileContents: String = tryExportingHtml(ids)._1
    assert(firstNewFileContents.contains("<a href=\"http://www.onemodel.org/downloads/testfile.txt\">test file download</a>"), "unexpected file contents:  " + firstNewFileContents)
  }

}
