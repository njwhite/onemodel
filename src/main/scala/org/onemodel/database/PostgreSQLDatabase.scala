/*  This file is part of OneModel, a program to manage knowledge.
    Copyright in each year of 2003, 2004, 2010, 2011, and 2013-2015 inclusive, Luke A Call; all rights reserved.
    OneModel is free software, distributed under a license that includes honesty, the Golden Rule, guidelines around binary
    distribution, and the GNU Affero General Public License as published by the Free Software Foundation, either version 3
    of the License, or (at your option) any later version.  See the file LICENSE for details.
    OneModel is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more details.
    You should have received a copy of the GNU Affero General Public License along with OneModel.  If not, see <http://www.gnu.org/licenses/>

  ---------------------------------------------------
  If we ever do port to another database, create the Database interface (removed around 2014-1-1 give or take) and see other changes at that time.
  An alternative method is to use jdbc escapes (but this actually might be even more work?):  http://jdbc.postgresql.org/documentation/head/escapes.html  .
  Another alternative is a layer like JPA, ibatis, hibernate  etc etc.

*/
package org.onemodel.database

import java.io.{PrintWriter, StringWriter}
import java.sql.{Connection, DriverManager, ResultSet, Statement}

import org.onemodel.model._
import org.onemodel.{OmDatabaseException, OmException, OmFileTransferException}
import org.postgresql.largeobject.{LargeObject, LargeObjectManager}

import scala.annotation.tailrec

/** Some methods are here on the object, so that PostgreSQLDatabaseTest can call destroyTables on test data.
  */
object PostgreSQLDatabase {
  val dbNamePrefix = "om_"
  val MIXED_CLASSES_EXCEPTION = "All the entities in a group should be of the same class."
  val systemEntityName = ".system-use-only"
  val classDefiningEntityGroupName = "class-defining entities"
  val theHASrelationTypeName = "has"

  def destroyTables(inDbNameWithoutPrefix: String, username: String, password: String) {
    Class.forName("org.postgresql.Driver")
    val conn: Connection = DriverManager.getConnection("jdbc:postgresql:" + dbNamePrefix + inDbNameWithoutPrefix, username, password)
    conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE)
    destroyTables_helper(conn)
  }

  private def destroyTables_helper(connIn: Connection) {
    // Doing these individually so that if one fails (not previously existing, such as testing or a new installation), the others can proceed (drop method
    // ignores that exception).

    drop("table", "TextAttribute", connIn)
    drop("table", "QuantityAttribute", connIn)
    drop("table", "RelationToEntity", connIn)
    drop("table", "action", connIn)
    drop("table", "EntitiesInAGroup", connIn)
    drop("table", "RelationToGroup", connIn)
    drop("table", "grupo", connIn)
    drop("table", "DateAttribute", connIn)
    drop("table", "BooleanAttribute", connIn)
    // The next line is to invoke the trigger that will clean out Large Objects (FileAttributeContent...) from the table pg_largeobject.
    // The LO cleanup doesn't happen (trigger not invoked) w/ just a drop (or truncate), but does on delete.  For more info see the wiki reference
    // link among those down in this file below "create table FileAttribute".
    try {
      dbAction("delete from FileAttributeContent", callerChecksRowCountEtc = true, connIn)
    } catch {
      case e: Exception =>
        val sw: StringWriter = new StringWriter()
        e.printStackTrace(new PrintWriter(sw))
        val messages = sw.toString
        if (!messages.contains("does not exist")) throw e
    }
    drop("table", "FileAttributeContent", connIn)
    drop("table", "FileAttribute", connIn)
    drop("table", "RelationType", connIn)
    drop("table", "class, Entity", connIn)
    drop("sequence", "EntityKeySequence", connIn)
    drop("sequence", "ClassKeySequence", connIn)
    drop("sequence", "TextAttributeKeySequence", connIn)
    drop("sequence", "QuantityAttributeKeySequence", connIn)
    drop("sequence", "RelationTypeKeySequence", connIn)
    drop("sequence", "ActionKeySequence", connIn)
    drop("sequence", "RelationToGroupKeySequence", connIn)
    drop("sequence", "DateAttributeKeySequence", connIn)
    drop("sequence", "BooleanAttributeKeySequence", connIn)
    drop("sequence", "FileAttributeKeySequence", connIn)
  }

  private def drop(sqlType: String, name: String, connIn: Connection) {
    try dbAction("drop " + escapeQuotesEtc(sqlType) + " " + escapeQuotesEtc(name), callerChecksRowCountEtc = false, connIn)
    catch {
      case e: Exception =>
        val sw: StringWriter = new StringWriter()
        e.printStackTrace(new PrintWriter(sw))
        val messages = sw.toString
        if (!messages.contains("does not exist")) throw e
    }
  }

  /** For text fields (which by the way should be surrounded with single-quotes ').  Best to use this
    * with only one field at a time, so you don't escape the single-ticks that *surround* the field.
    */
  def escapeQuotesEtc(s: String): String = {
    var result: String = s
    /*
    //both of these seem to work to embed a ' (single quote) in interactive testing w/ psql: the SQL standard
    //way (according to http://www.postgresql.org/docs/9.1/interactive/sql-syntax-lexical.html#SQL-SYNTAX-STRINGS )
    //    update entity set (name) = ('len''gth4') where id=-9223372036854775807;
    //...or the postgresql extension way (also works for: any char (\a is a), c-like (\b, \f, \n, \r, \t), or
    //hex (eg \x27), or "\u0027 (?) , \U0027 (?)  (x = 0 - 9, A - F)  16 or 32-bit
    //hexadecimal Unicode character value"; see same url above):
    //    update entity set (name) = (E'len\'gth4') where id=-9223372036854775807;
    */
    // we don't have to do much: see the odd string that works ok, searching for "!@#$%" etc in PostgreSQLDatabaseTest.
    result = result.replaceAll("'", "''")
    // there is probably a different/better/right way to do this, possibly using the psql functions quote_literal or quote_null,
    // or maybe using "escape" string constants (a postgresql extension to the sql standard). But it needs some thought, and maybe
    // this will work for now, unless someone needs to access the DB in another form. Kludgy, yes. It's on the fix list.
    result = result.replaceAll(";", "\59")
    result
  }

  def unEscapeQuotesEtc(s: String): String = {
    // don't have to do the single-ticks ("'") because the db does that automatically when returning data (see PostgreSQLDatabaseTest).

    var result: String = s
    result = result.replaceAll("\59", ";")
    result
  }

  /** Returns the # of rows affected.
    */
  def dbAction(sqlIn: String, callerChecksRowCountEtc: Boolean = false, connIn: Connection): Long = {
    var rowsAffected = -1
    var st: Statement = null
    val isCreateDropOrAlterStatement = sqlIn.toLowerCase.startsWith("create ") || sqlIn.toLowerCase.startsWith("drop ") ||
                                       sqlIn.toLowerCase.startsWith("alter ")
    try {
      st = connIn.createStatement
      checkForBadSql(sqlIn)
      rowsAffected = st.executeUpdate(sqlIn)

      // idea: not sure whether these checks belong here really.  Might be worth research
      // to see how often warnings actually should be addressed, & how to routinely tell the difference. If so, do the same at the
      // other place(s) that use getWarnings.
      val warnings = st.getWarnings
      if (warnings != null && !warnings.toString.contains("NOTICE: CREATE TABLE / PRIMARY KEY will create implicit index")) {
        throw new Exception("Warnings from postgresql. Matters? Says: " + warnings)
      }
      if (!callerChecksRowCountEtc && !isCreateDropOrAlterStatement && rowsAffected != 1) {
        throw new Exception("Affected " + rowsAffected + " rows instead of 1?? SQL was: " + sqlIn)
      }
      rowsAffected
    } catch {
      case e: Exception =>
        val msg = "Exception while processing sql: "
        throw new OmDatabaseException(msg + sqlIn, e)
    } finally {
      if (st != null) st.close()
    }
  }

  def checkForBadSql(s: String) {
    if (s.contains(";")) {
      // it seems that could mean somehow an embedded sql is in a normal command, as an attack vector. We don't need
      // to write like that, nor accept it from outside. This & any similar needed checks should happen reliably
      // at the lowest level before the database for security.  If text needs the problematic character(s), it should
      // be escaped prior (see escapeQuotesEtc for writing data, and where we read data).
      throw new Exception("Input can't contain ';'")
    }
  }

}


/**
 * Any code that would change when we change storage systems (like from postgresql to
 * an object database or who knows), goes in this class. Implements the Database
 * interface.
 * <br><br>
 * Note that any changes to the database structures (or constraints, etc) whatsoever should
 * ALWAYS have the following: <ul>
 * <li>Constraints, rules, functions, stored procedures, or triggers
 * or something to enforce data integrity and referential integrity at the database level,
 * whenever possible. When this is impossible, it should be discussed on the developer mailing
 * so that we can consider putting it in the right place in the code, with the goal of
 * greatest simplicity and reliability.</li>
 * <li>Put these things in the auto-creation steps of the DB class. See createDatabase() and createTables().</li>
 * <li>Add comments to that part of the code, explaining the change or requirement, as needed.</li>
 * <li>Any changes (as anywhere in this system) should be done in a test-first manner, for anything that
 * could go wrong, along these lines: First write a test that demonstrates the issue and fails, then
 * write code to correct the issue, then re-run the test to see the successful outcome. This helps keep our
 * regression suite current, and could even help think through design issues without over-complicating things.
 * </ul>
 *
 * This creates a new instance of Database. By default, auto-commit is on unless you explicitly open a transaction; then
 * auto-commit will be off until you rollbackTrans() or commitTrans(), at which point auto-commit is
 * turned back on.
 */
class PostgreSQLDatabase(username: String, var password: String) {
  private val ENTITY_ONLY_SELECT_PART: String = "SELECT e.id"
  protected var mConn: Connection = null
  Class.forName("org.postgresql.Driver")
  connect(PostgreSQLDatabase.dbNamePrefix + username, username, password)
  // clear the password from memory. Is there a better way?:
  password = null
  System.gc()
  System.gc()
  if (!modelTablesExist) {
    createTables()
    createDefaultData()
  }

  def connect(inDbNameWithoutPrefix: String, username: String, password: String) {
    try if (mConn != null) mConn.close()
    catch {case e: Exception => throw new RuntimeException(e)}
    mConn = DriverManager.getConnection("jdbc:postgresql:" + inDbNameWithoutPrefix, username, password)
    mConn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE)
  }

  def dbAction(sqlIn: String, callerChecksRowCountEtc: Boolean = false): Long = {
    PostgreSQLDatabase.dbAction(sqlIn, callerChecksRowCountEtc, mConn)
  }

  /** Does standard setup for a "OneModel" database, such as when starting up for the first time, or when creating a test system. */
  def createTables() {
    beginTrans()
    try {
      dbAction("create sequence EntityKeySequence minvalue " + minIdValue)

      // id must be "unique not null" in ANY database used, because it is a primary key. "PRIMARY KEY" is the same.
      dbAction("create table Entity (" +
               "id bigint DEFAULT nextval('EntityKeySequence') PRIMARY KEY, " +
               "name varchar(" + entityNameLength + ") NOT NULL, " +
               "class_id bigint, " +
               // 'archived' is only on Entity for now, to see if rows from related tables just don't show up because we
               // never link to them (never seeing the linking Entity rows), so they're effectively hidden/archived too.
               // At some point we could consider moving all those rows (entities & related...) to separate tables instead,
               // for performance/space if needed (including 'public').
               "archived boolean NOT NULL default false, " +
               "archived_date bigint check ((archived is false and archived_date is null) OR (archived and archived_date is not null)), " +
               // intended to be a readonly date: the (*java*-style numeric: milliseconds since 1970-1-1 or such) when this row was inserted (ie, when the
               // entity object was created in the db):
               "insertion_date bigint not null, " +
               // null in the 'public' field means 'undecided' (effectively "false", but a different nuance,e.g. in case user wants to remember to decide later)
               "public boolean " +
               ") ")
      // not unique, but for convenience/speed:
      dbAction("create index entity_lower_name on Entity (lower(NAME))")

      dbAction("create sequence ClassKeySequence minvalue " + minIdValue)

      // the name here doesn't have to be the same name as in the related Entity record, (since it's not a key, and it might not make sense to match).
      dbAction("create table Class (" +
               "id bigint DEFAULT nextval('ClassKeySequence') PRIMARY KEY, " +
               "name varchar(" + classNameLength + ") NOT NULL, " +
               "defining_entity_id bigint UNIQUE NOT NULL, " +
               "CONSTRAINT valid_related_to_entity_id FOREIGN KEY (defining_entity_id) REFERENCES entity (id) " +
               ") ")
      dbAction("alter table entity add CONSTRAINT valid_related_to_class_id FOREIGN KEY (class_id) REFERENCES class (id)")


      dbAction("create sequence RelationTypeKeySequence minvalue " + minIdValue)
      // this table "inherits" from Entity (each relation type is an Entity) but we use homegrown "inheritance" for that to make it
      // easier to port to databases that don't have postgresql-like inheritance built in. It inherits from Entity so that as Entity
      // expands (i.e., context-based naming or whatever), we'll automatically get the benefits, in objects based on this table (at least
      // that's the idea at this moment...) --Luke Call 8/2003  That may have been a mistake, more of a nuisance to coordinate
      // them than having 2 tables (luke, 2013-11-1).
      // inherits from Entity; see RelationConnection for more info.
      // Note, 2014-07: At one point I considered whether this concept overlaps with that of class, but now I think they are quite separate.  This table
      // could fill the concept of an entity that *is* a relationship, containing e.g. the date a relationship began, or any other attributes that are not about
      // either participant, but about the relationship itself.  One such use could be: I "have" a physical object, I and the object being entities with
      // classes, and the "have" is not a regular generic "have" type (as defined by the system at first startup), but a particular one (maybe RelationType
      // should be renamed to "RelationEntity" or something: think about all this some more: more use cases etc).
      dbAction("create table RelationType (" +
               "entity_id bigint PRIMARY KEY, " +
               "name_in_reverse_direction varchar(" + relationTypeNameLength + "), " +
               // valid values are "BI ","UNI","NON"-directional for this relationship. example: parent/child is unidirectional. sibling is bidirectional,
               // and for nondirectional
               // see Controller's mention of "nondir" and/or elsewhere for comments
               "directionality char(3) CHECK (directionality in ('BI','UNI','NON')), " +
               "CONSTRAINT valid_rel_entity_id FOREIGN KEY (entity_id) REFERENCES Entity (id) ON DELETE CASCADE " +
               ") ")


      dbAction("create sequence QuantityAttributeKeySequence minvalue " + minIdValue)
      // the parent_id is the key for the entity on which this quantity info is recorded; for other meanings see comments on
      // Entity.addQuantityAttribute(...).
      // id must be "unique not null" in ANY database used, because it is the primary key.
      // FOR COLUMN MEANINGS, SEE ALSO THE COMMENTS IN CREATEQUANTITYATTRIBUTE.
      dbAction("create table QuantityAttribute (" +
               "id bigint DEFAULT nextval('QuantityAttributeKeySequence') PRIMARY KEY, " +
               "parent_id bigint NOT NULL, " +
               //refers to a unit (an entity), like "meters":
               "unit_id bigint NOT NULL, " +
               // eg, 50.0:
               "quantity_number double precision not null, " +
               //eg, length (an entity):
               "attr_type_id bigint not null, " +
               // see "create table RelationToEntity" for comments about dates' meanings.
               "valid_on_date bigint, " +
               "observation_date bigint not null, " +
               "CONSTRAINT valid_unit_id FOREIGN KEY (unit_id) REFERENCES entity (id), " +
               "CONSTRAINT valid_attr_type_id FOREIGN KEY (attr_type_id) REFERENCES entity (id), " +
               "CONSTRAINT valid_parent_id FOREIGN KEY (parent_id) REFERENCES entity (id) ON DELETE CASCADE " +
               ") ")
      dbAction("create index quantity_parent_id on QuantityAttribute (parent_id)")

      dbAction("create sequence TextAttributeKeySequence minvalue " + minIdValue)
      // the parent_id is the key for the entity on which this text info is recorded; for other meanings see comments on
      // Entity.addQuantityAttribute(...).
      // id must be "unique not null" in ANY database used, because it is the primary key.
      dbAction("create table TextAttribute (" +
               "id bigint DEFAULT nextval('TextAttributeKeySequence') PRIMARY KEY, " +
               "parent_id bigint NOT NULL, " +
               "textValue text NOT NULL, " +
               //eg, serial number (which would be an entity)
               "attr_type_id bigint not null, " +
               // see "create table RelationToEntity" for comments about dates' meanings.
               "valid_on_date bigint, " +
               "observation_date bigint not null, " +
               "CONSTRAINT valid_attr_type_id FOREIGN KEY (attr_type_id) REFERENCES entity (id), " +
               "CONSTRAINT valid_parent_id FOREIGN KEY (parent_id) REFERENCES entity (id) ON DELETE CASCADE " +
               ") ")
      dbAction("create index text_parent_id on TextAttribute (parent_id)")

      dbAction("create sequence DateAttributeKeySequence minvalue " + minIdValue)
      dbAction("create table DateAttribute (" +
               "id bigint DEFAULT nextval('DateAttributeKeySequence') PRIMARY KEY, " +
               "parent_id bigint NOT NULL, " +
               //eg, due on, done on, should start on, started on on... (which would be an entity)
               "attr_type_id bigint not null, " +
               "date bigint not null, " +
               "CONSTRAINT valid_attr_type_id FOREIGN KEY (attr_type_id) REFERENCES entity (id), " +
               "CONSTRAINT valid_parent_id FOREIGN KEY (parent_id) REFERENCES entity (id) ON DELETE CASCADE " +
               ") ")
      dbAction("create index date_parent_id on DateAttribute (parent_id)")

      dbAction("create sequence BooleanAttributeKeySequence minvalue " + minIdValue)
      dbAction("create table BooleanAttribute (" +
               "id bigint DEFAULT nextval('BooleanAttributeKeySequence') PRIMARY KEY, " +
               "parent_id bigint NOT NULL, " +
               // allowing nulls because a template might not have value, and a task might not have a "done/not" setting yet (if unknown)?
               "booleanValue boolean, " +
               //eg, isDone (which would be an entity)
               "attr_type_id bigint not null, " +
               // see "create table RelationToEntity" for comments about dates' meanings.
               "valid_on_date bigint, " +
               "observation_date bigint not null, " +
               "CONSTRAINT valid_attr_type_id FOREIGN KEY (attr_type_id) REFERENCES entity (id), " +
               "CONSTRAINT valid_parent_id FOREIGN KEY (parent_id) REFERENCES entity (id) ON DELETE CASCADE " +
               ") ")
      dbAction("create index boolean_parent_id on BooleanAttribute (parent_id)")

      dbAction("create sequence FileAttributeKeySequence minvalue " + minIdValue)
      dbAction("create table FileAttribute (" +
               "id bigint DEFAULT nextval('FileAttributeKeySequence') PRIMARY KEY, " +
               "parent_id bigint NOT NULL, " +
               //eg, refers to a type like txt: i.e., could be like mime types, extensions, or mac fork info, etc (which would be an entity in any case).
               "attr_type_id bigint NOT NULL, " +
               "description text NOT NULL, " +
               "original_file_date bigint NOT NULL, " +
               "stored_date bigint NOT NULL, " +
               "original_file_path text NOT NULL, " +
               // now that i already wrote this, maybe storing 'readable' is overkill since the system has to read it to store its content. Maybe there's a use.
               "readable boolean not null, " +
               "writable boolean not null, " +
               "executable boolean not null, " +
               //moved to other table: "contents bit varying NOT NULL, " +
               "size bigint NOT NULL, " +
               // this is the md5 hash in hex (just to see if doc has become corrupted; not intended for security/encryption)
               "md5hash char(32) NOT NULL, " +
               "CONSTRAINT valid_attr_type_id FOREIGN KEY (attr_type_id) REFERENCES entity (id), " +
               "CONSTRAINT valid_parent_id FOREIGN KEY (parent_id) REFERENCES entity (id) ON DELETE CASCADE " +
               ") ")
      dbAction("create index file_parent_id on FileAttribute (parent_id)")
      // about oids and large objects, blobs: here are some reference links (but consider also which version of postgresql is running):
      //  https://duckduckgo.com/?q=postgresql+large+binary+streams
      //  http://www.postgresql.org/docs/9.1/interactive/largeobjects.html
      //  https://wiki.postgresql.org/wiki/BinaryFilesInDB
      //  http://jdbc.postgresql.org/documentation/80/binary-data.html
      //  http://artofsystems.blogspot.com/2008/07/mysql-postgresql-and-blob-streaming.html
      //  http://stackoverflow.com/questions/2069541/postgresql-jdbc-and-streaming-blobs
      //  http://giswiki.hsr.ch/PostgreSQL_-_Binary_Large_Objects
      dbAction("CREATE TABLE FileAttributeContent (" +
               "file_attribute_id bigint PRIMARY KEY, " +
               "contents_oid lo NOT NULL, " +
               "CONSTRAINT valid_fileattr_id FOREIGN KEY (file_attribute_id) REFERENCES fileattribute (id) ON DELETE CASCADE " +
               ")")
      // This trigger exists because otherwise the binary data from large objects doesn't get cleaned up when the related rows are deleted. For details
      // see the links just above (especially the wiki one).
      dbAction("CREATE TRIGGER om_contents_oid_cleanup BEFORE UPDATE OR DELETE ON fileattributecontent " +
               "FOR EACH ROW EXECUTE PROCEDURE lo_manage(contents_oid)")

      //Example: a relationship between a state and various counties might be set up like this:
      // The state and each county are Entities. A RelationType (which is an Entity with some
      // additional columns) is bi- directional and indicates some kind of containment relationship, for example between
      // state & counties. In the RelationToEntity table there would be a row whose rel_type_id points to the described RelationType,
      // whose entity_id_1 points to the state Entity, and whose entity_id_2 points to a given county Entity. There would be
      // additional rows for each county, varying only in the value in entity_id_2.
      // And example of something non(?)directional would be where the relationship is identical no matter which way you go, like
      // two human acquaintances). The relationship between a state and county is not the same in reverse. Haven't got a good
      // unidirectional example, so maybe it can be eliminated? (Or maybe it would be something where the "child" doesn't "know"
      // the "parent"--like an electron in an atom? -- revu notes or see what Mark Butler thinks.
      // --Luke Call 8/2003.
      dbAction("create table RelationToEntity (" +
               // (Not creating an artificial key here (as in TextAttribute.id, etc) so as not to artificially limit the # of possible RelationToEntity rows.)
               //for lookup in RelationType table, eg "has":
               "rel_type_id bigint NOT NULL, " +
               // what is related (see RelationConnection for "related to what" (related_to_entity_id):
               "entity_id_1 bigint NOT NULL, " +
               // entity_id in RelAttr table is related to what other entity(ies):
               "entity_id_2 bigint NOT NULL, " +
               //valid on date can be null (means no info), or 0 (means 'for all time', not 1970 or whatever that was. At least make it a 1 in that case),
               //or the date it first became valid/true:
               "valid_on_date bigint, " +
               //whenever first observed
               "observation_date bigint not null, " +
               "PRIMARY KEY (rel_type_id, entity_id_1, entity_id_2), " +
               "CONSTRAINT valid_rel_type_id FOREIGN KEY (rel_type_id) REFERENCES RelationType (entity_id) ON DELETE CASCADE, " +
               "CONSTRAINT valid_related_to_entity_id_1 FOREIGN KEY (entity_id_1) REFERENCES entity (id) ON DELETE CASCADE, " +
               "CONSTRAINT valid_related_to_entity_id_2 FOREIGN KEY (entity_id_2) REFERENCES entity (id) ON DELETE CASCADE " +
               ") ")
      dbAction("create index entity_id_1 on RelationToEntity (entity_id_1)")
      dbAction("create index entity_id_2 on RelationToEntity (entity_id_2)")


      dbAction("create sequence ActionKeySequence minvalue " + minIdValue)
      dbAction("create table Action (" +
               "id bigint DEFAULT nextval('ActionKeySequence') PRIMARY KEY, " +
               "class_id bigint NOT NULL, " +
               "name varchar(" + entityNameLength + ") NOT NULL, " +
               "action varchar(" + entityNameLength + ") NOT NULL, " +
               "CONSTRAINT valid_related_to_class_id FOREIGN KEY (class_id) REFERENCES Class (id) ON DELETE CASCADE " +
               ") ")
      dbAction("create index action_class_id on Action (class_id)")

      // Would rename this sequence to match the table it's used in now, but the cmd "alter sequence relationtogroupkeysequence rename to groupkeysequence;"
      // doesn't rename the name inside the sequence, and keeping the old name is easier for now than deciding whether to do something about that (more info
      // if you search the WWW for "postgresql bug 3619".
      dbAction("create sequence RelationToGroupKeySequence minvalue " + minIdValue)
      // This table named "grupo" because otherwise some queries (like "drop table group") don't work unless "group" is quoted, which doesn't work
      // with mixed case; but forcing the dropped names to lowercase and quoted also prevented dropping class and entity in the same command, it seemed.
      // Avoiding the word "group" as a table in sql might prevent other errors too.
      dbAction("create table grupo (" +
               "id bigint DEFAULT nextval('RelationToGroupKeySequence') PRIMARY KEY, " +
               "name varchar(" + entityNameLength + ") NOT NULL, " +
               // intended to be a readonly date: the (*java*-style numeric: milliseconds since 1970-1-1 or such) when this row was inserted (ie, when the
               // entity object was created in the db):
               "insertion_date bigint not null, " +
               "allow_mixed_classes boolean NOT NULL " +
               ") ")

      dbAction("create table RelationToGroup (" +
               // the entity id of the entity whose attribute (subgroup, RTG) this is:
               "entity_id bigint NOT NULL, " +
               "rel_type_id bigint NOT NULL, " +
               "group_id bigint NOT NULL, " +
               //  idea: Should the 2 dates be eliminated? The code is there, including in the parent class, and they might be useful,
               //  maybe no harm while we wait & see.
               // see "create table RelationToEntity" for comments about dates' meanings.
               "valid_on_date bigint, " +
               "observation_date bigint not null, " +
               "PRIMARY KEY (entity_id, rel_type_id, group_id), " +
               "CONSTRAINT valid_reltogrp_entity_id FOREIGN KEY (entity_id) REFERENCES entity (id) ON DELETE CASCADE, " +
               "CONSTRAINT valid_reltogrp_rel_type_id FOREIGN KEY (rel_type_id) REFERENCES relationType (entity_id), " +
               "CONSTRAINT valid_reltogrp_group_id FOREIGN KEY (group_id) REFERENCES grupo (id) ON DELETE CASCADE " +
               ") ")
      dbAction("create index RTG_entity_id on RelationToGroup (entity_id)")
      dbAction("create index RTG_group_id on RelationToGroup (group_id)")

      /* This table maintains a 1-to-many connection between one entity, and many others in a particular group that it contains.
      Uhhh, clarify terms?: the table below is a (1) "relationship table" (aka relationship entity--not an OM entity but at a lower layer) which tracks
      those entities which are part of a particular group.  The nature of the (2) "relation"-ship between that group of entities and the entity that "has"
      them (or other relationtype to them...) is described by the table RelationToGroup, which is instead of a regular old (3) "RelationToEntity" because #3
      just
      relates Entities to other Entities.  Or in other words, #2 (RelationToGroup) has notes about the tie from Entities to groups of Entities,
      where the specific entities in that group are listed in #1 (this table below).  And the type of relation between them (has, contains,
      is acquainted with...?) is in the 4) relationtogroup table's reference to the relationtype table (or its "rel_type_id"). Got it?
      (Good, then let's not confuse things by mentioning that postgresql refers to *every* table (and more?) as a "relation" because that's another
      context altogether, another use of the word.)
      */
      dbAction("create table EntitiesInAGroup (" +
               "group_id bigint NOT NULL" +
               ", entity_id bigint NOT NULL" +
               ", sorting_index bigint not null" +
               // the key is really the group_id + entity_id, and the sorting_index is just in an index so we can cheaply order query results
               // When sorting_index was part of the key there were ongoing various problems because the rest of the system (like reordering results, but
               // probably also other issues) wasn't ready to handle two of the same entity in a group.
               ", PRIMARY KEY (group_id, entity_id)" +
               ", CONSTRAINT valid_group_id FOREIGN KEY (group_id) REFERENCES grupo (id) ON DELETE CASCADE" +
               ", CONSTRAINT valid_entity_id FOREIGN KEY (entity_id) REFERENCES entity (id)" +
               // make it so the sorting_index must also be unique for each group (otherwise we have sorting problems):
               ", constraint noDupSortingIndexes unique (group_id, sorting_index)" +
               ") ")
      dbAction("create index EntitiesInAGroup_id on EntitiesInAGroup (entity_id)")
      dbAction("create index EntitiesInAGroup_sorted on EntitiesInAGroup (group_id, entity_id, sorting_index)")

      commitTrans()
    } catch {
      case e: Exception => throw rollbackWithCatch(e)
    }
  }

  def findAllEntityIdsByName(inName: String, caseSensitive: Boolean = false): Option[List[Long]] = {
    // idea: see if queries like this are using the expected index (run & ck the query plan). Tests around that, for benefit of future dbs? Or, just wait for
    // a performance issue then look at it?
    val sql = "select id from entity where (not archived) and " + {
      if (caseSensitive) "name = '" + inName + "'"
      else "lower(name) = lower('" + inName + "'" + ")"
    }
    val rows = dbQuery(sql, "Long")

    if (rows.isEmpty) None
    else {
      var results: List[Long] = Nil
      for (row <- rows) {
        results = row(0).get.asInstanceOf[Long] :: results
      }
      Some(results.reverse)
    }
  }

  // See comment in ImportExport.processUriContent method which uses it, about where the code should really go. Not sure if that idea includes this
  // method or not.
  def findFIRSTClassIdByName(inName: String, caseSensitive: Boolean = false): Option[Long] = {
    // idea: see if queries like this are using the expected index (run & ck the query plan). Tests around that, for benefit of future dbs? Or, just wait for
    // a performance issue then look at it?
    val nameClause = {
      if (caseSensitive) "name = '" + inName + "'"
      else "lower(name) = lower('" + inName + "'" + ")"
    }
    val sql = "select id from class where " + nameClause + " order by id limit 1"
    val rows = dbQuery(sql, "Long")

    if (rows.isEmpty) None
    else {
      var results: List[Long] = Nil
      for (row <- rows) {
        results = row(0).get.asInstanceOf[Long] :: results
      }
      if (results.size > 1) throw new OmDatabaseException("Expected 1 row (wanted just the first one), found " + results.size + " rows.")
      Some(results.head)
    }
  }

  /** Case-insensitive. */
  def findEntityOnlyIdsByName(inName: String): Option[List[Long]] = {
    // idea: see if queries like this are using the expected index (run & ck the query plan). Tests around that, for benefit of future dbs? Or, just wait for
    // a performance issue then look at it?
    val rows = dbQuery("select id from entity where (not archived) and lower(name) = lower('" + inName + "') " + limitToEntitiesOnly(ENTITY_ONLY_SELECT_PART)
                       , "Long")
    if (rows.isEmpty) None
    else {
      var results: List[Long] = Nil
      for (row <- rows) {
        results = row(0).get.asInstanceOf[Long] :: results
      }
      Some(results.reverse)
    }
  }

  def createDefaultData() {
    // idea: what tests are best, around this, vs. simply being careful in upgrade scripts?
    val ids: Option[List[Long]] = findEntityOnlyIdsByName(PostgreSQLDatabase.systemEntityName)
    // will probably have to change the next line when things grow/change, and say, we're doing upgrades not always a new system:
    require(ids.isEmpty)

    // public=false, guessing at best value, since the world wants your modeled info, not details about your system internals (which might be...unique & personal
    // somehow)?:
    val systemEntityId = createEntity(PostgreSQLDatabase.systemEntityName, isPublicIn = Some(false))
    // public=true, since can't think of why not:
    val existenceEntityId = createEntity("existence", isPublicIn = Some(true))

    //idea: as probably mentioned elsewhere, this "BI" (and other strings?) should be replaced with a constant somewhere (or enum?)!
    val relTypeId = createRelationType(PostgreSQLDatabase.theHASrelationTypeName, "is had by", "BI")

    createRelationToEntity(relTypeId, systemEntityId, existenceEntityId, Some(System.currentTimeMillis()), System.currentTimeMillis())

    // the intent of this group is user convenience: the app shouldn't rely on this group to find classDefiningEntities, but use the relevant table.
    // idea: REALLY, this should probably be replaced with a query to the class table: so, when queries as menu options are part of the OM
    // features, put them all there instead.
    // It is set to allowMixedClassesInGroup just because no current known reason not to, will be interesting to see what comes of it.
    createGroupAndRelationToGroup(systemEntityId, relTypeId, PostgreSQLDatabase.classDefiningEntityGroupName, allowMixedClassesInGroupIn = true,
                                  Some(System.currentTimeMillis()), System.currentTimeMillis(), callerManagesTransactionsIn = false)

    // NOTICE: code should not rely on this name, but on data in the tables.
    /*val (classId, entityId) = */ createClassAndItsDefiningEntity("person-template")
  }

  /** Returns the classId and entityId, in a tuple. */
  def createClassAndItsDefiningEntity(inName: String): (Long, Long) = {
    // The name doesn't have to be the same on the entity and the defining class, but why not for now.
    val name: String = escapeQuotesEtc(inName)
    if (name == null || name.length == 0) throw new Exception("Name must have a value.")
    val classId: Long = getNewKey("ClassKeySequence")
    val entityId: Long = getNewKey("EntityKeySequence")
    beginTrans()
    try {
      // Start the entity w/ a NULL class_id so that it can be inserted w/o the class present, then update it afterward; constraints complain otherwise.
      // Idea: instead of doing in 3 steps, could specify 'deferred' on the 'not null'
      // constraint?: (see file:///usr/share/doc/postgresql-doc-9.1/html/sql-createtable.html).
      dbAction("INSERT INTO Entity (id, insertion_date, name, class_id) VALUES (" + entityId + "," + System.currentTimeMillis() + ",'" + name + "', NULL)")
      dbAction("INSERT INTO Class (id, name, defining_entity_id) VALUES (" + classId + ",'" + name + "', " + entityId + ")")
      dbAction("update Entity set (class_id) = (" + classId + ") where id=" + entityId)
    } catch {
      case e: Exception =>
        rollbackTrans()
        throw e
    }
    commitTrans()

    val classGroupId = getSystemEntitysClassGroupId
    if (classGroupId.isDefined) {
      addEntityToGroup(classGroupId.get, entityId)
    }

    (classId, entityId)
  }

  /** Returns the id of a specific group under the system entity.  This group is the one that contains class-defining entities. */
  def getSystemEntitysClassGroupId: Option[Long] = {
    val ids: Option[List[Long]] = findEntityOnlyIdsByName(PostgreSQLDatabase.systemEntityName)
    require(ids.get.size == 1)
    val systemEntityId = ids.get.head

    // idea: maybe this stuff would be less breakable by the user if we put this kind of info in some system table
    // instead of in this group. (See also method createDefaultData).  Or maybe it doesn't matter, since it's just a user convenience. Hmm.
    val classDefiningGroupId = findRelationToAndGroup_OnEntity(systemEntityId, Some(PostgreSQLDatabase.classDefiningEntityGroupName))._2
    if (classDefiningGroupId.isEmpty) {
      // no exception thrown here because really this group is a convenience for the user to see things, not a requirement. Maybe a user message would be best:
      System.err.println("Unable to find, from the entity " + PostgreSQLDatabase.systemEntityName + "(" + systemEntityId + "), " +
                         "any connection to its expected contained group " +
                         PostgreSQLDatabase.classDefiningEntityGroupName + ".  If it was deleted, it could be replaced if you want the convenience of finding" +
                         " class-defining " +
                         "entities in it.")
    }
    classDefiningGroupId
  }

  def deleteClassAndItsDefiningEntity(inClassId: Long) {
    beginTrans()
    try {
      val definingEntityId: Long = getClassData(inClassId)(1).get.asInstanceOf[Long]
      val classGroupId = getSystemEntitysClassGroupId
      if (classGroupId.isDefined) {
        removeEntityFromGroup(classGroupId.get, definingEntityId, callerManagesTransactionIn = true)
      }
      updateEntityOnlyClass(definingEntityId, None, callerManagesTransactions = true)
      deleteObjectById("class", inClassId, callerManagesTransactions = true)
      deleteObjectById("entity", definingEntityId, callerManagesTransactions = true)
    } catch {
      case e: Exception =>
        rollbackTrans()
        throw e
    }
    commitTrans()
  }

  /** Returns at most 1 row's info (relationTypeId, groupId), and a boolean indicating if more were available.  If 0 rows are found, returns (None,false),
    * so this expects the caller
    * to know there is only one or deal with the None.
    */
  def findRelationToAndGroup_OnEntity(inEntityId: Long, inGroupName: Option[String] = None): (Option[Long], Option[Long], Boolean) = {
    val nameCondition = if (inGroupName.isDefined) {
      val name = escapeQuotesEtc(inGroupName.get)
      "g.name='" + name + "'"
    } else
      "true"

    // limit 2 so we know and can return whether more were available:
    val rows = dbQuery("select rtg.rel_type_id, g.id from relationtogroup rtg, grupo g where rtg.group_id=g.id and rtg.entity_id=" + inEntityId + " and " +
                       nameCondition + " order by id limit 2", "Long,Long")
    // there could be none found, or more than one, but:
    if (rows.isEmpty)
      (None, None, false)
    else {
      val row = rows.head
      val relTypeId: Option[Long] = Some(row(0).get.asInstanceOf[Long])
      val groupId: Option[Long] = Some(row(1).get.asInstanceOf[Long])
      (relTypeId, groupId, rows.size > 1)
    }
  }

  /** Returns at most 1 id, and a boolean indicating if more were available.  If 0 rows are found, returns (None,false), so this expects the caller
    * to know there is only one or deal with the None.
    */
  def findRelationType(inTypeName: String): (Option[Long], Boolean) = {
    val name = escapeQuotesEtc(inTypeName)
    val rows = dbQuery("select entity_id from entity e, relationtype rt where e.id=rt.entity_id and name='" + name + "' order by id limit 2", "Long")
    // there could be none found, or more than one, but
    if (rows.isEmpty) (None, false)
    else {
      val row = rows.head
      val id: Option[Long] = Some(row(0).get.asInstanceOf[Long])
      val foundMoreThanOne: Boolean = rows.size > 1
      (id, foundMoreThanOne)
    }
  }

  /** Indicates whether the database setup has been done. */
  def modelTablesExist: Boolean = doesThisExist("select count(*) from pg_class where relname='entity'")

  /** Used, for example, when test code is finished with its test data. Be careful. */
  def destroyTables() {
    PostgreSQLDatabase.destroyTables_helper(mConn)
  }

  /**
   * Saves data for a quantity attribute for a Entity (i.e., "6 inches length").<br>
   * parentIdIn is the key of the Entity for which the info is being saved.<br>
   * inUnitId represents a Entity; indicates the unit for this quantity (i.e., liters or inches).<br>
   * inNumber represents "how many" of the given unit.<br>
   * attrTypeIdIn represents the attribute type and also is a Entity (i.e., "volume" or "length")<br>
   * validOnDateIn represents the date on which this began to be true (seems it could match the observation date if needed,
   * or guess when it was definitely true);
   * NULL means unknown, 0 means it is asserted true for all time. inObservationDate is the date the fact was observed. <br>
   * <br>
   * We store the dates in
   * postgresql (at least) as bigint which should be the same size as a java long, with the understanding that we are
   * talking about java-style dates here; it is my understanding that such long's can also be negative to represent
   * dates long before 1970, or positive for dates long after 1970. <br>
   * <br>
   * In the case of inNumber, note
   * that the postgresql docs give some warnings about the precision of its real and "double precision" types. Given those
   * warnings and the fact that I haven't investigated carefully (as of 9/2002) how the data will be saved and read
   * between the java float type and the postgresql types, I am using "double precision" as the postgresql data type,
   * as a guess to try to lose as
   * little information as possible, and I'm making this note to you the reader, so that if you care about the exactness
   * of the data you can do some research and let us know what you find.
   * <p/>
   * Re dates' meanings: see usage notes elsewhere in code (like inside createTables).
   */
  def createQuantityAttribute(inParentId: Long, inAttrTypeId: Long, inUnitId: Long, inNumber: Float, inValidOnDate: Option[Long],
                              inObservationDate: Long): /*id*/ Long = {
    val id: Long = getNewKey("QuantityAttributeKeySequence")
    dbAction("insert into QuantityAttribute (id, parent_id, unit_id, quantity_number, attr_type_id, valid_on_date, observation_date) " +
             "values (" + id + "," + inParentId + "," + inUnitId + "," + inNumber + "," + inAttrTypeId + "," +
             (if (inValidOnDate.isEmpty) "NULL" else inValidOnDate.get) + "," + inObservationDate + ")")
    id
  }

  def escapeQuotesEtc(s: String): String = {
    PostgreSQLDatabase.escapeQuotesEtc(s)
  }

  def checkForBadSql(s: String) {
    PostgreSQLDatabase.checkForBadSql(s)
  }

  def updateQuantityAttribute(inId: Long, inParentId: Long, inAttrTypeId: Long, inUnitId: Long, inNumber: Float, inValidOnDate: Option[Long],
                              inObservationDate: Long) {
    dbAction("update QuantityAttribute set (unit_id, quantity_number, attr_type_id, valid_on_date, observation_date) = (" + inUnitId + "," +
             "" + inNumber + "," + inAttrTypeId + "," + (if (inValidOnDate.isEmpty) "NULL" else inValidOnDate.get) + "," +
             "" + inObservationDate + ") where id=" + inId + " and  parent_id=" + inParentId)
  }

  def updateTextAttribute(inId: Long, inParentId: Long, inAttrTypeId: Long, inText: String, inValidOnDate: Option[Long], inObservationDate: Long) {
    val text: String = escapeQuotesEtc(inText)
    dbAction("update TextAttribute set (textValue, attr_type_id, valid_on_date, observation_date) = ('" + text + "'," + inAttrTypeId + "," +
             "" + (if (inValidOnDate.isEmpty) "NULL" else inValidOnDate.get) + "," + inObservationDate + ") where id=" + inId + " and  " +
             "parent_id=" + inParentId)
  }

  def updateDateAttribute(inId: Long, inParentId: Long, inDate: Long, inAttrTypeId: Long) {
    dbAction("update DateAttribute set (date, attr_type_id) = (" + inDate + "," + inAttrTypeId + ") where id=" + inId + " and  " +
             "parent_id=" + inParentId)
  }

  def updateBooleanAttribute(inId: Long, inParentId: Long, inAttrTypeId: Long, inBoolean: Boolean, inValidOnDate: Option[Long], inObservationDate: Long) {
    dbAction("update BooleanAttribute set (booleanValue, attr_type_id, valid_on_date, observation_date) = (" + inBoolean + "," + inAttrTypeId + "," +
             "" + (if (inValidOnDate.isEmpty) "NULL" else inValidOnDate.get) + "," + inObservationDate + ") where id=" + inId + " and  " +
             "parent_id=" + inParentId)
  }

  // We don't update the dates, path, size, hash because we set those based on the file's own timestamp, path current date,
  // & contents when it is written. So the only
  // point to having an update method might be the attribute type & description.
  // AND THAT: The validOnDate for a file attr shouldn't ever be None/NULL like with other attrs, because it is the file date in the filesystem before it was
  // read into OM.
  def updateFileAttribute(inId: Long, inParentId: Long, inAttrTypeId: Long, inDescription: String) {
    dbAction("update FileAttribute set (description, attr_type_id) = ('" + inDescription + "'," + inAttrTypeId + ")" +
             " where id=" + inId + " and parent_id=" + inParentId)
  }

  // first take on this: might have a use for it later.  It's tested, and didn't delete, but none known now. Remove?
  def updateFileAttribute(inId: Long, inParentId: Long, inAttrTypeId: Long, inDescription: String, originalFileDateIn: Long, storedDateIn: Long,
                          originalFilePathIn: String, readableIn: Boolean, writableIn: Boolean, executableIn: Boolean, sizeIn: Long, md5hashIn: String) {
    dbAction("update FileAttribute set " +
             " (description, attr_type_id, original_file_date, stored_date, original_file_path, readable, writable, executable, size, md5hash) =" +
             " ('" + inDescription + "'," + inAttrTypeId + "," + originalFileDateIn + "," + storedDateIn + ",'" + originalFilePathIn + "'," +
             " " + readableIn + "," + writableIn + "," + executableIn + "," +
             " " + sizeIn + "," +
             " '" + md5hashIn + "')" +
             " where id=" + inId + " and parent_id=" + inParentId)
  }

  def updateEntityOnlyName(inId: Long, nameIn: String) {
    val name: String = escapeQuotesEtc(nameIn)
    dbAction("update Entity set (name) = ('" + name + "') where id=" + inId)
  }

  def updateEntityOnlyPublicStatus(inId: Long, value: Option[Boolean]) {
    dbAction("update Entity set (public) = (" +
             (if (value.isEmpty) "NULL" else if (value.get) "true" else "false") +
             ") where id=" + inId)
  }

  def updateClassAndDefiningEntityName(classIdIn: Long, name: String): Long = {
    beginTrans()
    updateClassName(classIdIn, name)
    val entityId: Long = new EntityClass(this, classIdIn).getDefiningEntityId
    updateEntityOnlyName(entityId, name)
    commitTrans()
    entityId
  }

  def updateClassName(inId: Long, nameIn: String) {
    val name: String = escapeQuotesEtc(nameIn)
    dbAction("update class set (name) = ('" + name + "') where id=" + inId)
  }

  def updateEntityOnlyClass(entityId: Long, classId: Option[Long], callerManagesTransactions: Boolean = false) {
    if (!callerManagesTransactions) beginTrans()
    dbAction("update Entity set (class_id) = (" +
             (if (classId.isEmpty) "NULL" else classId.get) +
             ") where id=" + entityId)
    val groupIds = dbQuery("select group_id from EntitiesInAGroup where entity_id=" + entityId, "Long")
    for (row <- groupIds) {
      val groupId = row(0).get.asInstanceOf[Long]
      val mixedClassesAllowed: Boolean = areMixedClassesAllowed(groupId)
      if ((!mixedClassesAllowed) && hasMixedClasses(groupId)) {
        rollbackTrans()
        throw new OmException(PostgreSQLDatabase.MIXED_CLASSES_EXCEPTION)
      }
    }
    if (!callerManagesTransactions) commitTrans()
  }

  def updateRelationType(inId: Long, inName: String, inNameInReverseDirection: String, inDirectionality: String) {
    require(inName != null)
    require(inName.length > 0)
    require(inNameInReverseDirection != null)
    require(inNameInReverseDirection.length > 0)
    require(inDirectionality != null)
    require(inDirectionality.length > 0)
    val nameInReverseDirection: String = escapeQuotesEtc(inNameInReverseDirection)
    val name: String = escapeQuotesEtc(inName)
    val directionality: String = escapeQuotesEtc(inDirectionality)
    beginTrans()
    try {
      dbAction("update Entity set (name) = ('" + name + "') where id=" + inId)
      dbAction("update RelationType set (name_in_reverse_direction, directionality) = ('" + nameInReverseDirection + "', " +
               "'" + directionality + "') where entity_id=" + inId)
    } catch {
      case e: Exception => throw rollbackWithCatch(e)
    }
    commitTrans()
  }

  /** Re dates' meanings: see usage notes elsewhere in code (like inside createTables). */
  def createTextAttribute(inParentId: Long, inAttrTypeId: Long, inText: String, inValidOnDate: Option[Long] = None,
                          inObservationDate: Long = System.currentTimeMillis()): /*id*/ Long = {
    val text: String = escapeQuotesEtc(inText)
    val id: Long = getNewKey("TextAttributeKeySequence")
    dbAction("insert into TextAttribute (id, parent_id, textvalue, attr_type_id, valid_on_date, observation_date) " +
             "values (" + id + "," + inParentId + ",'" + text + "'," + inAttrTypeId + "," +
             "" + (if (inValidOnDate.isEmpty) "NULL" else inValidOnDate.get) + "," + inObservationDate + ")")
    id
  }

  def createDateAttribute(parentIdIn: Long, attrTypeIdIn: Long, dateIn: Long): /*id*/ Long = {
    val id: Long = getNewKey("DateAttributeKeySequence")
    dbAction("insert into DateAttribute (id, parent_id, attr_type_id, date) " +
             "values (" + id + "," + parentIdIn + ",'" + attrTypeIdIn + "'," + dateIn + ")")
    id
  }

  def createBooleanAttribute(parentIdIn: Long, attrTypeIdIn: Long, booleanIn: Boolean, validOnDateIn: Option[Long], observationDateIn: Long): /*id*/ Long = {
    val id: Long = getNewKey("BooleanAttributeKeySequence")
    dbAction("insert into BooleanAttribute (id, parent_id, booleanvalue, attr_type_id, valid_on_date, observation_date) " +
             "values (" + id + "," + parentIdIn + ",'" + booleanIn + "'," + attrTypeIdIn + "," +
             "" + (if (validOnDateIn.isEmpty) "NULL" else validOnDateIn.get) + "," + observationDateIn + ")")
    id
  }

  def createFileAttribute(parentIdIn: Long, attrTypeIdIn: Long, descriptionIn: String, originalFileDateIn: Long, storedDateIn: Long,
                          originalFilePathIn: String, readableIn: Boolean, writableIn: Boolean, executableIn: Boolean, sizeIn: Long,
                          md5hashIn: String, inputStreamIn: java.io.FileInputStream): /*id*/ Long = {
    val description: String = escapeQuotesEtc(descriptionIn)
    // (Next 2 for completeness but there shouldn't ever be a problem if other code is correct.)
    val originalFilePath: String = escapeQuotesEtc(originalFilePathIn)
    // Escaping the md5hash string shouldn't ever matter, but security is more important than the md5hash:
    val md5hash: String = escapeQuotesEtc(md5hashIn)
    var obj: LargeObject = null
    var id: Long = 0
    try {
      id = getNewKey("FileAttributeKeySequence")
      beginTrans()
      dbAction("insert into FileAttribute (id, parent_id, attr_type_id, description, original_file_date, stored_date, original_file_path, readable, writable," +
               " executable, size, md5hash)" +
               " values (" + id + "," + parentIdIn + "," + attrTypeIdIn + ",'" + description + "'," + originalFileDateIn + "," + storedDateIn + "," +
               " '" + originalFilePath + "', " + readableIn + ", " + writableIn + ", " + executableIn + ", " + sizeIn + ",'" + md5hash + "')")
      // from the example at:   http://jdbc.postgresql.org/documentation/80/binary-data.html & info
      // at http://jdbc.postgresql.org/documentation/publicapi/org/postgresql/largeobject/LargeObjectManager.html & its links.
      val lobjManager: LargeObjectManager = mConn.asInstanceOf[org.postgresql.PGConnection].getLargeObjectAPI
      val oid: Long = lobjManager.createLO()
      obj = lobjManager.open(oid, LargeObjectManager.WRITE)
      val buffer = new Array[Byte](2048)
      var numBytesRead = 0
      var total: Long = 0
      @tailrec
      def saveFileToDb() {
        numBytesRead = inputStreamIn.read(buffer)
        // (intentional style violation, for readability):
        if (numBytesRead == -1) Unit
        else {
          // just once by a test subclass is enough to mess w/ the md5sum.
          if (total == 0) damageBuffer(buffer)

          obj.write(buffer, 0, numBytesRead)
          total += numBytesRead
          saveFileToDb()
        }
      }
      saveFileToDb()
      if (total != sizeIn) {
        throw new Exception("Transferred " + total + " bytes instead of " + sizeIn + "??")
      }
      dbAction("INSERT INTO FileAttributeContent (file_attribute_id, contents_oid) VALUES (" + id + "," + oid + ")")

      val (success, errMsgOption) = verifyFileAttributeContentIntegrity(id)
      if (!success) {
        throw new OmFileTransferException("Failure to successfully upload file content: " + errMsgOption.getOrElse("(verification provided no error message? " +
                                                                                                                   "how?)"))
      }
      commitTrans()
      id
    } catch {
      case e: Exception =>
        rollbackTrans()
        throw e
    } finally {
      if (obj != null)
        try {
          obj.close()
        } catch {
          case e: Exception =>
          // not sure why this fails sometimes, if it's a bad thing or not, but for now not going to be stuck on it.
          // idea: look at the source code.
        }
    }
  }

  /** Re dates' meanings: see usage notes elsewhere in code (like inside createTables). */
  def createRelationToEntity(inRelationTypeId: Long, inEntityId1: Long, inEntityId2: Long, inValidOnDate: Option[Long], inObservationDate: Long) {
    dbAction("INSERT INTO RelationToEntity (rel_type_id, entity_id_1, entity_id_2, valid_on_date, observation_date) " +
             "VALUES (" + inRelationTypeId + "," + inEntityId1 + ", " + inEntityId2 + ", " +
             "" + (if (inValidOnDate.isEmpty) "NULL" else inValidOnDate.get) + "," + inObservationDate + ")")
  }

  /** Re dates' meanings: see usage notes elsewhere in code (like inside createTables). */
  def updateRelationToEntity(inRelationTypeId: Long, inEntityId1: Long, inEntityId2: Long, inValidOnDate: Option[Long], inObservationDate: Long) {
    dbAction("UPDATE RelationToEntity SET (rel_type_id, valid_on_date, observation_date)" +
             " = (" + inRelationTypeId + "," + (if (inValidOnDate.isEmpty) "NULL" else inValidOnDate.get) + "," + inObservationDate + ")" +
             " where rel_type_id=" + inRelationTypeId + " and entity_id_1=" + inEntityId1 + " and entity_id_2=" + inEntityId2)
  }

  def createGroup(inName: String, allowMixedClassesInGroupIn: Boolean = false): Long = {
    val name: String = escapeQuotesEtc(inName)
    val groupId: Long = getNewKey("RelationToGroupKeySequence")
    dbAction("INSERT INTO grupo (id, name, insertion_date, allow_mixed_classes) " +
             "VALUES (" +
             groupId + ", '" + name + "', " + System.currentTimeMillis() + ", " + (if (allowMixedClassesInGroupIn) "TRUE" else "FALSE") + ")")
    groupId
  }

  /** I.e., make it so the entity has a group in it, which can contain entities.
    * Re dates' meanings: see usage notes elsewhere in code (like inside createTables).
    */
  def createGroupAndRelationToGroup(inEntityId: Long, inRelationTypeId: Long, newGroupNameIn: String, allowMixedClassesInGroupIn: Boolean = false,
                                    inValidOnDate: Option[Long], inObservationDate: Long, callerManagesTransactionsIn: Boolean = false): Long = {
    if (!callerManagesTransactionsIn) beginTrans()
    val groupId: Long = createGroup(newGroupNameIn, allowMixedClassesInGroupIn)
    createRelationToGroup(inEntityId, inRelationTypeId, groupId, inValidOnDate, inObservationDate)
    if (!callerManagesTransactionsIn) commitTrans()
    groupId
  }

  /** I.e., make it so the entity has a relation to a new entity in it.
    * Re dates' meanings: see usage notes elsewhere in code (like inside createTables).
    */
  def createEntityAndRelationToEntity(inEntityId: Long, inRelationTypeId: Long, newEntityNameIn: String, isPublicIn: Option[Boolean],
                                      inValidOnDate: Option[Long], inObservationDate: Long, callerManagesTransactionsIn: Boolean = false): Long = {
    val name: String = escapeQuotesEtc(newEntityNameIn)
    if (!callerManagesTransactionsIn) beginTrans()
    val newEntityId: Long = createEntity(name, isPublicIn = isPublicIn)
    createRelationToEntity(inRelationTypeId, inEntityId, newEntityId, inValidOnDate, inObservationDate)
    if (!callerManagesTransactionsIn) commitTrans()
    newEntityId
  }

  /** I.e., make it so the entity has a group in it, which can contain entities.
    * Re dates' meanings: see usage notes elsewhere in code (like inside createTables).
    */
  def createRelationToGroup(inEntityId: Long, inRelationTypeId: Long, groupIdIn: Long, inValidOnDate: Option[Long], inObservationDate: Long) {
    dbAction("INSERT INTO RelationToGroup (entity_id, rel_type_id, group_id, valid_on_date, observation_date) " +
             "VALUES (" +
             inEntityId + "," + inRelationTypeId + "," + groupIdIn +
             ", " + (if (inValidOnDate.isEmpty) "NULL" else inValidOnDate.get) + "," + inObservationDate + ")")
  }

  def updateGroup(groupIdIn: Long, inName: String, allowMixedClassesInGroupIn: Boolean = false) {
    val name: String = escapeQuotesEtc(inName)
    dbAction("UPDATE grupo SET (name, allow_mixed_classes)" +
             " = ('" + name + "', " + (if (allowMixedClassesInGroupIn) "TRUE" else "FALSE") +
             ") where id=" + groupIdIn)
  }

  /** Re dates' meanings: see usage notes elsewhere in code (like inside createTables).
    */
  def updateRelationToGroup(entityIdIn: Long, inRelationTypeId: Long, groupIdIn: Long, inValidOnDate: Option[Long], inObservationDate: Long) {
    dbAction("UPDATE RelationToGroup SET (valid_on_date, observation_date)" +
             " = (" + (if (inValidOnDate.isEmpty) "NULL" else inValidOnDate.get) + "," + inObservationDate + ")" +
             " where entity_id=" + entityIdIn + " and rel_type_id=" + inRelationTypeId + " and group_id=" + groupIdIn)
  }

  /** Trying it out with the entity's previous sortingIndex (or whatever is passed in) in case it's more convenient, say, when brainstorming a
    * list then grouping them afterward, to keep them in the same order.  Might be better though just to put them all at the end; can see....
    */
  def moveEntityToNewGroup(targetGroupIdIn: Long, oldGroupIdIn: Long, entityIdIn: Long, sortingIndexIn: Long) {
    beginTrans()
    addEntityToGroup(targetGroupIdIn, entityIdIn, Some(sortingIndexIn), callerManagesTransactionsIn = true)
    removeEntityFromGroup(oldGroupIdIn, entityIdIn, callerManagesTransactionIn = true)
    if (isEntityInGroup(targetGroupIdIn, entityIdIn) && !isEntityInGroup(oldGroupIdIn, entityIdIn)) {
      commitTrans()
    } else {
      rollbackTrans()
      throw new OmException("Entity didn't get moved properly.  Retry: if predictably reproducible, it should be diagnosed.")
    }
  }

  // idea: this needs a test, and/or combining with findIdWhichIsNotKeyOfAnyEntity.
  def findUnusedSortingIndex(groupIdIn: Long): Long = {
    //better idea?  This should be fast because we start in remote regions and return as soon as an unused id is found, probably
    //only one iteration, ever.
    val startingIndex: Long = maxIdValue - 1

    @tailrec def findUnusedSortingIndex_helper(gId: Long, workingIndex: Long, counter: Long): Long = {
      if (sortingIndexInUse(gId, workingIndex)) {
        if (workingIndex == maxIdValue) {
          // means we did a full loop across all possible ids!?  Doubtful. Probably would turn into a performance problem long before. It's a bug.
          throw new Exception("No available index found which is not already used for this group & entity. How would so many be used?")
        }
        // idea: see comment at similar location in findIdWhichIsNotKeyOfAnyEntity
        if (counter > 1000) throw new Exception("Very unexpected, but could it be that you are running out of available sorting indexes for a single group!!?" +
                                                " Have someone check, before you need to create, for example, a thousand more entities.")
        findUnusedSortingIndex_helper(gId, workingIndex - 1, counter + 1)
      } else workingIndex
    }

    findUnusedSortingIndex_helper(groupIdIn, startingIndex, 0)
  }

  /** I.e., insert an entity into a group of entities. Using a default value for the sorting_index because user can set it if/as desired;
    * the max (ie putting it at the end) might be the least often surprising if the user wonders where one went....
    */
  def addEntityToGroup(groupIdIn: Long, containedEntityIdIn: Long, sortingIndexIn: Option[Long] = None,
                       callerManagesTransactionsIn: Boolean = false) {
    if (!callerManagesTransactionsIn) beginTrans()

    // start from the beginning index, if it's the 1st record (otherwise later sorting/renumbering gets messed up if we start w/ the last #):
    val sortingIndex = {
      val index = if (sortingIndexIn.isDefined) sortingIndexIn.get
      else if (getGroupEntryCount(groupIdIn) == 0) minIdValue
      else maxIdValue
      if (sortingIndexInUse(groupIdIn, index))
        findUnusedSortingIndex(groupIdIn)
      else
        index
    }

    dbAction("insert into EntitiesInAGroup (group_id, entity_id, sorting_index) values (" + groupIdIn + "," + containedEntityIdIn + "," +
             "" + sortingIndex + ")")
    // idea: do this check sooner in this method?:
    val mixedClassesAllowed: Boolean = areMixedClassesAllowed(groupIdIn)
    if ((!mixedClassesAllowed) && hasMixedClasses(groupIdIn)) {
      rollbackTrans()
      throw new Exception(PostgreSQLDatabase.MIXED_CLASSES_EXCEPTION)
    }
    if (!callerManagesTransactionsIn) commitTrans()
  }

  def areMixedClassesAllowed(groupId: Long): Boolean = {
    val rows = dbQuery("select allow_mixed_classes from grupo where id =" + groupId, "Boolean")
    val mixedClassesAllowed: Boolean = rows.head(0).get.asInstanceOf[Boolean]
    mixedClassesAllowed
  }

  def hasMixedClasses(inRelationToGroupId: Long): Boolean = {
    // Enforce that all entities in so-marked groups have the same class (or they all have no class; too bad).
    // (This could be removed or modified, but some user scripts attached to groups might (someday?) rely on their uniformity, so this
    // and the fact that you can have a group all of which don't have any class, is experimental.  This is optional, per
    // group.  I.e., trying it that way now to see whether it removes desired flexibility
    // at a cost higher than the benefit of uniformity for later user code operating on groups.  This might be better in a constraint,
    // but after trying for a while I hadn't made the syntax work right.

    // (Had to ask for them all and expect 1, instead of doing a count, because for some reason "select count(class_id) ... group by class_id" doesn't
    // group, and you get > 1 when I wanted just 1. This way it seems to work if I just check the # of rows returned.)
    val numClassesInGroupsEntities = dbQuery("select class_id from EntitiesInAGroup eiag, entity e" +
                                             " where eiag.entity_id=e.id and group_id=" + inRelationToGroupId +
                                             " and class_id is not null" +
                                             " group by class_id",
                                             "Long").size
    // nulls don't show up in a count(class_id), so get those separately
    val numNullClassesInGroupsEntities = extractRowCountFromCountQuery("select count(entity_id) from EntitiesInAGroup eiag, entity e" +
                                                                       " where eiag.entity_id=e.id" + " and group_id=" + inRelationToGroupId +
                                                                       " and class_id is NULL ")
    if (numClassesInGroupsEntities > 1 ||
        (numClassesInGroupsEntities >= 1 && numNullClassesInGroupsEntities > 0)) {
      true
    } else false
  }

  def createEntity(inName: String, inClassId: Option[Long] = None, isPublicIn: Option[Boolean] = None): /*id*/ Long = {
    val name: String = escapeQuotesEtc(inName)
    if (name == null || name.length == 0) throw new Exception("Name must have a value.")
    val id: Long = getNewKey("EntityKeySequence")
    val sql: String = "INSERT INTO Entity (id, insertion_date, name, public" + (if (inClassId.isDefined) ", class_id" else "") + ")" +
                      " VALUES (" + id + "," + System.currentTimeMillis() + ",'" + name + "'," +
                      (if (isPublicIn.isEmpty) "NULL" else isPublicIn.get) +
                      (if (inClassId.isDefined) "," + inClassId.get else "") + ")"
    dbAction(sql)
    id
  }

  def createRelationType(inName: String, inNameInReverseDirection: String, inDirectionality: String): /*id*/ Long = {
    val nameInReverseDirection: String = escapeQuotesEtc(inNameInReverseDirection)
    val name: String = escapeQuotesEtc(inName)
    val directionality: String = escapeQuotesEtc(inDirectionality)
    if (name == null || name.length == 0) throw new Exception("Name must have a value.")
    beginTrans()
    try {
      val id: Long = getNewKey("EntityKeySequence")
      dbAction("INSERT INTO Entity (id, insertion_date, name) VALUES (" + id + "," + System.currentTimeMillis() + ",'" + name + "')")
      dbAction("INSERT INTO RelationType (entity_id, name_in_reverse_direction, directionality) VALUES (" + id + ",'" + nameInReverseDirection + "'," +
               "'" + directionality + "')")
      commitTrans()
      id
    } catch {
      case e: Exception => throw rollbackWithCatch(e)
    }
  }

  def rollbackWithCatch(t: Throwable): Throwable = {
    var rollbackException: Option[Throwable] = None
    try {
      rollbackTrans()
    } catch {
      case e: Exception =>
        rollbackException = Some(e)
    }
    if (rollbackException.isEmpty) t
    else new Exception("See the chained messages for BOTH the cause of rollback failure, AND for the original failure.").initCause(rollbackException.get
                                                                                                                                   .initCause(t))
  }

  def deleteEntity(inId: Long, callerManagesTransactionsIn: Boolean = false) = {
    // idea: (also on task list i think but) we should not delete entities until dealing with their use as attrtypeids etc!
    if (!callerManagesTransactionsIn) beginTrans()
    deleteObjects("EntitiesInAGroup", "where entity_id=" + inId, -1, callerManagesTransactions = true)
    deleteObjects("Entity", "where id=" + inId, 1, callerManagesTransactions = true)
    if (!callerManagesTransactionsIn) commitTrans()
  }

  def archiveEntity(inId: Long, callerManagesTransactionsIn: Boolean = false) = {
    archiveObjects("Entity", "where id=" + inId, 1)
  }

  def deleteQuantityAttribute(inID: Long) = deleteObjectById("QuantityAttribute", inID)

  def deleteTextAttribute(inID: Long) = deleteObjectById("TextAttribute", inID)

  def deleteDateAttribute(inID: Long) = deleteObjectById("DateAttribute", inID)

  def deleteBooleanAttribute(inID: Long) = deleteObjectById("BooleanAttribute", inID)

  def deleteFileAttribute(inID: Long) = deleteObjectById("FileAttribute", inID)

  def deleteRelationToEntity(inRelTypeId: Long, inEntityId1: Long, inEntityId2: Long) {
    deleteObjects("RelationToEntity", "where rel_type_id=" + inRelTypeId + " and entity_id_1=" + inEntityId1 + " and entity_id_2=" + inEntityId2)
  }

  def deleteRelationToGroup(entityIdIn: Long, relTypeIdIn: Long, groupIdIn: Long) {
    deleteObjects("RelationToGroup", "where entity_id=" + entityIdIn + " and rel_type_id=" + relTypeIdIn + " and group_id=" + groupIdIn)
  }

  def deleteGroupAndRelationsToIt(inId: Long) {
    beginTrans()
    val entityCount: Long = getGroupEntryCount(inId)
    deleteObjects("EntitiesInAGroup", "where group_id=" + inId, entityCount, callerManagesTransactions = true)
    val numGroups = getRelationToGroupCountByGroup(inId)
    deleteObjects("RelationToGroup", "where group_id=" + inId, numGroups, callerManagesTransactions = true)
    deleteObjects("grupo", "where id=" + inId, 1, callerManagesTransactions = true)
    commitTrans()
  }

  def removeEntityFromGroup(groupIdIn: Long, inContainedEntityId: Long, callerManagesTransactionIn: Boolean = false) {
    deleteObjects("EntitiesInAGroup", "where group_id=" + groupIdIn + " and entity_id=" + inContainedEntityId,
                  callerManagesTransactions = callerManagesTransactionIn)
  }

  /** I hope you have a backup. */
  def deleteGroupRelationsToItAndItsEntries(groupidIn: Long) {
    beginTrans()
    val entityCount = getGroupEntryCount(groupidIn)

    def deleteRelationToGroupAndALL_recursively(inGroupId: Long): (Long, Long) = {
      val entityIds: List[Array[Option[Any]]] = dbQuery("select entity_id from entitiesinagroup where group_id=" + inGroupId, "Long")
      val deletions1 = deleteObjects("entitiesinagroup", "where group_id=" + inGroupId, entityCount, callerManagesTransactions = true)
      // Have to delete these 2nd because of a constraint on EntitiesInAGroup:
      // idea: is there a temp table somewhere that these could go into instead, for efficiency?
      // idea: batch these, would be much better performance.
      // idea: BUT: what is the length limit: should we do it it sets of N to not exceed sql command size limit?
      // idea: (also on task list i think but) we should not delete entities until dealing with their use as attrtypeids etc!
      for (id <- entityIds) {
        deleteObjects("entity", "where id=" + id(0).get.asInstanceOf[Long], 1, callerManagesTransactions = true)
      }

      val deletions2 = 0
      //and finally:
      deleteObjects("RelationToGroup", "where group_id=" + inGroupId, callerManagesTransactions = true)
      deleteObjects("grupo", "where id=" + inGroupId, 1, callerManagesTransactions = true)
      (deletions1, deletions2)
    }
    val (deletions1, deletions2) = deleteRelationToGroupAndALL_recursively(groupidIn)
    require(deletions1 + deletions2 == entityCount)
    commitTrans()
  }

  def deleteRelationType(inID: Long) {
    // One possibility is that this should ALWAYS fail because it is done by deleting the entity, which cascades.
    // but that's more confusing to the programmer using the database layer's api calls, because they
    // have to know to delete an Entity instead of a RelationType. So we just do the desired thing here
    // instead, and the delete cascades.
    // Maybe those tables should be separated so this is its own thing? for performance/clarity?
    // like *attribute and relation don't have a parent 'attribute' table?  But see comments
    // in createTables where this one is created.
    deleteObjects("Entity", "where id=" + inID)
  }

  def getEntityCount: Long = extractRowCountFromCountQuery("SELECT count(1) from Entity where (not archived)")

  def getClassCount(inEntityId: Option[Long] = None): Long = {
    val whereClause = if (inEntityId.isDefined) " where defining_entity_id=" + inEntityId.get else ""
    extractRowCountFromCountQuery("SELECT count(1) from class" + whereClause)
  }

  def getSortingIndex(groupIdIn: Long, entityIdIn: Long): Long = {
    val row = dbQueryWrapperForOneRow("select sorting_index from EntitiesInAGroup where group_id=" + groupIdIn + " and entity_id=" + entityIdIn, "Long")
    row(0).get.asInstanceOf[Long]
  }

  def getHighestSortingIndex(groupIdIn: Long): Long = {
    val rows: List[Array[Option[Any]]] = dbQuery("select max(sorting_index) from EntitiesInAGroup where group_id=" + groupIdIn, "Long")
    require(rows.size == 1)
    rows.head(0).get.asInstanceOf[Long]
  }

  def renumberGroupSortingIndexes(relationToGroupIdIn: Long) {
    val groupSize: Long = getGroupEntryCount(relationToGroupIdIn)
    if (groupSize != 0) {
      val numberOfSegments = groupSize + 1
      val increment = maxIdValue / numberOfSegments * 2
      var next: Long = minIdValue
      //we could have a problem here no?, because the query returns !archived, and so we might assign the same sorting_index to an archived one & have an err?
      beginTrans()
      for (groupEntry <- getGroupEntriesData(relationToGroupIdIn)) {
        next += increment
        while (sortingIndexInUse(relationToGroupIdIn, next)) {
          // Renumbering can choose already-used numbers, because it always uses the same algorithm.  This causes a constraint violation (unique index), so
          // get around that with a (hopefully quick & simple) increment to get the next unused one.  If they're all used...that's a surprise.
          // Should also fix this bug in the case where it's near the end & the last #s are used: wrap around? when give err after too many loops: count?
          next += 1
        }
        require(next < maxIdValue)
        val id: Long = groupEntry(0).get.asInstanceOf[Long]
        updateEntityInAGroup(relationToGroupIdIn, id, next)
      }

      // require: just to confirm that the generally expected behavior happened, not a requirement other than that:
      // (didn't happen in case of newly added entries w/ default values....
      // idea: could investigate further...does it matter or imply anything for adding entries to *brand*-newly created groups? Is it related
      // to the fact that when doing that, the 2nd entry goes above, not below the 1st, and to move it down you have to choose the down 1 option
      // *twice* for some reason (sometimes??)? And to the fact that deleting an entry selects the one above, not below, for next highlighting?)
      // (See also a comment somewhere else 4 poss. issue that refers, related, to this method name.)
      //require((maxIDValue - next) < (increment * 2))

      commitTrans()
    }
  }

  def classLimit(limitByClass: Boolean, inClassId: Option[Long]): String = {
    if (limitByClass) {
      if (inClassId.isDefined) {
        " and e.class_id=" + inClassId.get + " "
      } else {
        " and e.class_id is NULL "
      }
    } else ""
  }

  /** Excludes those entities that are really relationtypes, attribute types, or quantity units.
    *
    * The parameter limitByClass decides whether any limiting is done at all: if true, the query is
    * limited to entities having the class specified by inClassId (even if that is None).
    *
    * The parameter classDefiningEntity *further* limits, if limitByClass is true, by omitting the classDefiningEntity from the results (e.g., to help avoid
    * counting that one when deciding whether it is OK to delete the class).
    * */
  def getEntitiesOnlyCount(inClassId: Option[Long] = None, limitByClass: Boolean = false,
                           classDefiningEntity: Option[Long] = None): Long = {
    extractRowCountFromCountQuery("SELECT count(1) from Entity e where (not archived) and true " +
                                  classLimit(limitByClass, inClassId) +
                                  (if (limitByClass && classDefiningEntity.isDefined) " and id != " + classDefiningEntity.get else "") +
                                  " and id in " +
                                  "(select id from entity " + limitToEntitiesOnly(ENTITY_ONLY_SELECT_PART) +
                                  ")")
  }

  def getRelationTypeCount: Long = extractRowCountFromCountQuery("select count(1) from RelationType")

  def getAttrCount(entityIdIn: Long): Long = {
    getQuantityAttributeCount(entityIdIn) +
    getTextAttributeCount(entityIdIn) +
    getRelationToEntityCount(entityIdIn) +
    getRelationToGroupCountByEntity(Some(entityIdIn)) +
    getDateAttributeCount(entityIdIn) +
    getBooleanAttributeCount(entityIdIn) +
    getFileAttributeCount(entityIdIn)
  }

  def getQuantityAttributeCount(inEntityId: Long): Long = {
    extractRowCountFromCountQuery("select count(1) from QuantityAttribute where parent_id=" + inEntityId)
  }

  def getTextAttributeCount(inEntityId: Long): Long = {
    extractRowCountFromCountQuery("select count(1) from TextAttribute where parent_id=" + inEntityId)
  }

  def getDateAttributeCount(inEntityId: Long): Long = {
    extractRowCountFromCountQuery("select count(1) from DateAttribute where parent_id=" + inEntityId)
  }

  def getBooleanAttributeCount(inEntityId: Long): Long = {
    extractRowCountFromCountQuery("select count(1) from BooleanAttribute where parent_id=" + inEntityId)
  }

  def getFileAttributeCount(inEntityId: Long): Long = {
    extractRowCountFromCountQuery("select count(1) from FileAttribute where parent_id=" + inEntityId)
  }

  def getRelationToEntityCount(inEntityId: Long): Long = {
    extractRowCountFromCountQuery("select count(1) from RelationToEntity where entity_id_1=" + inEntityId)
  }

  /** if 1st parm is None, gets all. */
  def getRelationToGroupCountByEntity(inEntityId: Option[Long]): Long = {
    extractRowCountFromCountQuery("select count(1) from relationtogroup" + (if (inEntityId.isEmpty) "" else " where entity_id=" + inEntityId.get))
  }

  def getRelationToGroupCountByGroup(inGroupId: Long): Long = {
    extractRowCountFromCountQuery("select count(1) from relationtogroup where group_id=" + inGroupId)
  }

  def getRelationToGroupsByGroup(groupIdIn: Long, startingIndexIn: Long, maxValsIn: Option[Long] = None): java.util.ArrayList[RelationToGroup] = {
    val sql: String = "select entity_id, rel_type_id, group_id, valid_on_date, observation_date from RelationToGroup " +
                      " where group_id=" + groupIdIn
    val earlyResults = dbQuery(sql, "Long,Long,Long,Long,Long")
    val finalResults = new java.util.ArrayList[RelationToGroup]
    // idea: should the remainder of this method be moved to RelationToGroup, so the persistence layer doesn't know anything about the Model? (helps avoid
    // circular
    // dependencies? is a cleaner design?)
    for (result <- earlyResults) {
      // None of these values should be of "None" type, so not checking for that. If they are it's a bug:
      //finalResults.add(result(0).get.asInstanceOf[Long], new Entity(this, result(1).get.asInstanceOf[Long]))
      val rtg: RelationToGroup = new RelationToGroup(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[Long], result(2).get.asInstanceOf[Long],
                                                     if (result(3).isEmpty) None else Some(result(3).get.asInstanceOf[Long]), result(4).get.asInstanceOf[Long])
      finalResults.add(rtg)
    }

    require(finalResults.size == earlyResults.size)
    finalResults
  }

  def getGroupCount: Long = {
    extractRowCountFromCountQuery("select count(1) from grupo")
  }

  /** Parameter includeArchivedEntities true/false means select only archived/non-archived entities; None there means BOTH archived and non-archived (all).
    */
  def getGroupEntryCount(groupIdIn: Long, includeArchivedEntities: Option[Boolean] = None): Long = {
    val archivedSqlCondition: String = if (includeArchivedEntities.isEmpty) "true"
    else if (includeArchivedEntities.get) "archived"
    else "(not archived)"
    extractRowCountFromCountQuery("select count(1) from entity e, EntitiesInAGroup eiag where e.id=eiag.entity_id and " + archivedSqlCondition + " and eiag" +
                                  ".group_id=" + groupIdIn)
  }

  /** For all groups to which the parameter belongs, returns a collection of the *containing* RelationToGroups, in the form of "entityName -> groupName"'s.
    * This is useful for example when one is about
    * to delete an entity and we want to warn first, showing where it is contained.
    */
  def getRelationToGroupDescriptionsContaining(inEntityId: Long, inLimit: Option[Long] = None): Array[String] = {
    val rows: List[Array[Option[Any]]] = dbQuery("select e.name, grp.name, grp.id from entity e, relationtogroup rtg, " +
                                                 "grupo grp where (not archived) and e.id = rtg.entity_id" +
                                                 " and rtg.group_id = grp.id and rtg.group_id in (SELECT group_id from entitiesinagroup where entity_id=" +
                                                 inEntityId + ")" +
                                                 " order by grp.id limit " + checkIfShouldBeAllResults(inLimit), "String,String,Long")
    var results: List[String] = Nil
    for (row <- rows) {
      val entityName = row(0).get.asInstanceOf[String]
      val groupName = row(1).get.asInstanceOf[String]
      results = entityName + "->" + groupName :: results
    }

    results.reverse.toArray
  }

  /** For a given group, find all the RelationToGroup's that contain entities that contain the provided group id.
    */
  def getContainingGroupsIds(groupIdIn: Long, inLimit: Option[Long] = Some(5)): List[Array[Option[Any]]] = {
    //get every entity that contains a rtg that contains this group:
    val containingEntityIdList: List[Array[Option[Any]]] = dbQuery("SELECT entity_id from relationtogroup where group_id=" + groupIdIn +
                                                                   " order by entity_id limit " + checkIfShouldBeAllResults(inLimit), "Long")
    var containingEntityIds: String = ""
    //for all those entity ids, get every rtg id containing that entity
    for (row <- containingEntityIdList) {
      val entityId: Long = row(0).get.asInstanceOf[Long]
      containingEntityIds += entityId
      containingEntityIds += ","
    }
    if (containingEntityIds.nonEmpty) {
      // remove the last comma
      containingEntityIds = containingEntityIds.substring(0, containingEntityIds.length - 1)
      val rtgRows: List[Array[Option[Any]]] = dbQuery("SELECT group_id from entitiesinagroup" +
                                                      " where entity_id in (" + containingEntityIds + ") order by group_id limit " +
                                                      checkIfShouldBeAllResults(inLimit), "Long")
      rtgRows
    } else Nil
  }

  def getCountOfGroupsContainingEntity(inEntityId: Long): Long = {
    extractRowCountFromCountQuery("select count(1) from EntitiesInAGroup eig, entity e where e.id=eig.entity_id and not e.archived" +
                                  " and eig.entity_id=" + inEntityId)
  }

  def isEntityInGroup(inGroupId: Long, inEntityId: Long): Boolean = {
    val num = extractRowCountFromCountQuery("select count(1) from EntitiesInAGroup eig, entity e where eig.entity_id=e.id and (not e.archived)" +
                                            " and group_id=" + inGroupId + " and entity_id=" + inEntityId)
    if (num > 1) throw new OmException("Entity " + inEntityId + " is in group " + inGroupId + " " + num + " times?? Should be 0 or 1.")
    num == 1
  }

  /** Before calling this, the caller should have made sure that any parameters it received in the form of
    * Strings should have been passed through escapeQuotesEtc FIRST, and ONLY THE RESULT SENT HERE.
    * Returns the # of results, and the results (a collection of rows, each row being its own collection).
    *
    * idea: probably should change the data types from List to Vector or other, once I finish reading about that.
    */
  private def dbQuery(sql: String, types: String): List[Array[Option[Any]]] = {
    // Note: pgsql docs say "Under the JDBC specification, you should access a field only once" (under the JDBC interface part).
    checkForBadSql(sql)
    // idea: results could change to a val and be filled w/ a recursive helper method; other vars might go away then too.
    var results: List[Array[Option[Any]]] = Nil
    val typesAsArray: Array[String] = types.split(",")
    var st: Statement = null
    var rs: ResultSet = null
    var rowCounter = 0
    try {
      st = mConn.createStatement
      rs = st.executeQuery(sql)
      // idea: (see comment at other use in this class, of getWarnings)
      // idea: maybe both uses of getWarnings should be combined into a method.
      val warnings = rs.getWarnings
      val warnings2 = st.getWarnings
      if (warnings != null || warnings2 != null) throw new Exception("Warnings from postgresql. Matters? Says: " + warnings + ", and " + warnings2)
      while (rs.next) {
        rowCounter += 1
        val row: Array[Option[Any]] = new Array[Option[Any]](typesAsArray.length)
        //1-based counter for db results, but array is 0-based, so will compensate w/ -1:
        var columnCounter = 0
        for (typeString: String <- typesAsArray) {
          // the for loop is to take is through all the columns in this row, as specified by the caller in the "types" parm.
          columnCounter += 1
          if (rs.getObject(columnCounter) == null) row(columnCounter - 1) = None
          else {
            if (typeString == "Float") {
              row(columnCounter - 1) = Some(rs.getFloat(columnCounter))
            } else if (typeString == "String") {
              row(columnCounter - 1) = Some(PostgreSQLDatabase.unEscapeQuotesEtc(rs.getString(columnCounter)))
            } else if (typeString == "Long") {
              row(columnCounter - 1) = Some(rs.getLong(columnCounter))
            } else if (typeString == "Boolean") {
              row(columnCounter - 1) = Some(rs.getBoolean(columnCounter))
            } else if (typeString == "Int") {
              row(columnCounter - 1) = Some(rs.getInt(columnCounter))
            } else throw new Exception("unexpected value: '" + typeString + "'")
          }
        }
        results = row :: results
      }
    } catch {
      case e: Exception => throw new Exception("Exception while processing sql: " + sql, e)
    } finally {
      if (rs != null) rs.close()
      if (st != null) st.close()
    }
    require(rowCounter == results.size)
    results.reverse
  }

  def dbQueryWrapperForOneRow(sql: String, types: String): Array[Option[Any]] = {
    val results = dbQuery(sql, types)
    if (results.size != 1) throw new Exception("Got " + results.size + " instead of 1 result from sql " + sql + "??")
    results.head
  }

  def getQuantityAttributeData(inQuantityId: Long): Array[Option[Any]] = {
    dbQueryWrapperForOneRow("select parent_id, unit_id, quantity_number, attr_type_id, valid_on_date, observation_date from QuantityAttribute " + "where id="
                            + inQuantityId,
                            "Long,Long,Float,Long,Long,Long")
  }

  def getRelationToEntityData(inRelTypeId: Long, inEntityId1: Long, inEntityId2: Long): Array[Option[Any]] = {
    dbQueryWrapperForOneRow("select valid_on_date, observation_date from RelationToEntity where rel_type_id=" + inRelTypeId + " and entity_id_1=" +
                            inEntityId1 + " " +
                            "and entity_id_2=" + inEntityId2,
                            "Long,Long")
  }

  def getGroupData(inId: Long): Array[Option[Any]] = {
    dbQueryWrapperForOneRow("select name, insertion_date, allow_mixed_classes from grupo where id=" + inId,
                            "String,Long,Boolean")
  }

  def getRelationToGroupData(entityId: Long, relTypeId: Long, groupId: Long): Array[Option[Any]] = {
    dbQueryWrapperForOneRow("select entity_id, rel_type_id, group_id, valid_on_date, observation_date from RelationToGroup " +
                            " where entity_id=" + entityId + " and rel_type_id=" + relTypeId + " and group_id=" + groupId,
                            "Long,Long,Long,Long,Long")
  }

  def getRelationTypeData(inId: Long): Array[Option[Any]] = {
    dbQueryWrapperForOneRow("select name, name_in_reverse_direction, directionality from RelationType r, Entity e where (not archived) and e.id=r.entity_id " +
                            "and r.entity_id=" +
                            inId,
                            "String,String,String")
  }

  // idea: combine all the methods that look like this (s.b. easier now, in scala, than java)
  def getTextAttributeData(inTextId: Long): Array[Option[Any]] = {
    dbQueryWrapperForOneRow("select parent_id, textValue, attr_type_id, valid_on_date, observation_date from TextAttribute where id=" + inTextId,
                            "Long,String,Long,Long,Long")
  }

  def getDateAttributeData(inDateId: Long): Array[Option[Any]] = {
    dbQueryWrapperForOneRow("select parent_id, date, attr_type_id from DateAttribute where id=" + inDateId,
                            "Long,Long,Long")
  }

  def getBooleanAttributeData(inBooleanId: Long): Array[Option[Any]] = {
    dbQueryWrapperForOneRow("select parent_id, booleanValue, attr_type_id, valid_on_date, observation_date from BooleanAttribute where id=" + inBooleanId,
                            "Long,Boolean,Long,Long,Long")
  }

  def getFileAttributeData(inFileId: Long): Array[Option[Any]] = {
    dbQueryWrapperForOneRow("select parent_id, description, attr_type_id, original_file_date, stored_date, original_file_path, readable, writable, " +
                            "executable, size, md5hash " +
                            " from FileAttribute where id=" + inFileId,
                            "Long,String,Long,Long,Long,String,Boolean,Boolean,Boolean,Long,String")
  }

  def getFileAttributeContent(fileAttributeIdIn: Long, outputStreamIn: java.io.OutputStream): (Long, String) = {
    def action(bufferIn: Array[Byte], startingIndexIn: Int, numBytesIn: Int) {
      outputStreamIn.write(bufferIn, startingIndexIn, numBytesIn)
    }
    actOnFileFromServer(fileAttributeIdIn, action)
  }

  def updateEntityInAGroup(groupIdIn: Long, entityIdIn: Long, sortingIndexIn: Long) {
    dbAction("update EntitiesInAGroup set (sorting_index) = (" + sortingIndexIn + ") where group_id=" + groupIdIn + " and  " +
             "entity_id=" + entityIdIn)
  }

  /** Returns whether the stored and calculated md5hashes match, and an error message when they don't.
    */
  def verifyFileAttributeContentIntegrity(fileAttributeIdIn: Long): (Boolean, Option[String]) = {
    // idea: combine w/ similar logic in FileAttribute.md5Hash?
    val d = java.security.MessageDigest.getInstance("MD5")
    def action(bufferIn: Array[Byte], startingIndexIn: Int, numBytesIn: Int) {
      d.update(bufferIn, startingIndexIn, numBytesIn)
    }
    val storedMd5Hash = actOnFileFromServer(fileAttributeIdIn, action)._2
    // outputs same as command 'md5sum <file>'.  It is a style violation (advanced feature) but it's what I found when searching for how to do it.
    val md5hash: String = d.digest.map(0xFF &).map {"%02x".format(_)}.foldLeft("") {_ + _}
    if (md5hash == storedMd5Hash) (true, None)
    else {
      (false, Some("Mismatched md5hashes: " + storedMd5Hash + " (stored in the md5sum db column) != " + md5hash + "(calculated from stored file contents)"))
    }
  }

  /** This is a no-op, called in actOnFileFromServer, that a test can customize to simulate a corrupted file on the server. */
  // (intentional style violation, for readability)
  def damageBuffer(buffer: Array[Byte]): Unit = Unit

  /** Returns the file size (having confirmed it is the same as the # of bytes processed), and the md5hash that was stored with the document.
    */
  def actOnFileFromServer(fileAttributeIdIn: Long, actionIn: (Array[Byte], Int, Int) => Unit): (Long, String) = {
    var obj: LargeObject = null
    try {
      // even though we're not storing data, the instructions (see createTables re this...) said to have it in a transaction.
      beginTrans()
      val lobjManager: LargeObjectManager = mConn.asInstanceOf[org.postgresql.PGConnection].getLargeObjectAPI
      val oidOption: Option[Long] = dbQueryWrapperForOneRow("select contents_oid from FileAttributeContent where file_attribute_id=" + fileAttributeIdIn,
                                                            "Long")(0).asInstanceOf[Option[Long]]
      if (oidOption.isEmpty) throw new Exception("No contents found for file attribute id " + fileAttributeIdIn)
      val oid: Long = oidOption.get
      obj = lobjManager.open(oid, LargeObjectManager.READ)
      val buffer = new Array[Byte](2048)
      var numBytesRead = 0
      var total: Long = 0
      @tailrec
      def readFileFromDbAndActOnIt() {
        numBytesRead = obj.read(buffer, 0, buffer.length)
        // (intentional style violation, for readability):
        if (numBytesRead <= 0) Unit
        else {
          // just once by a test subclass is enough to mess w/ the md5sum.
          if (total == 0) damageBuffer(buffer)

          actionIn(buffer, 0, numBytesRead)
          total += numBytesRead
          readFileFromDbAndActOnIt()
        }
      }
      readFileFromDbAndActOnIt()
      val resultOption = dbQueryWrapperForOneRow("select size, md5hash from fileattribute where id=" + fileAttributeIdIn, "Long,String")
      if (resultOption(0).isEmpty) throw new Exception("No result from query for fileattribute for id " + fileAttributeIdIn + ".")
      val (contentSize, md5hash) = (resultOption(0).get.asInstanceOf[Long], resultOption(1).get.asInstanceOf[String])
      if (total != contentSize) {
        throw new OmFileTransferException("Transferred " + total + " bytes instead of " + contentSize + "??")
      }
      commitTrans()
      (total, md5hash)
    } catch {
      case e: Exception =>
        rollbackTrans()
        throw e
    } finally {
      try {
        obj.close()
      } catch {
        case e: Exception =>
        // not sure why this fails sometimes, if it's a bad thing or not, but for now not going to be stuck on it.
        // idea: look at the source code.
      }
    }
  }

  def quantityAttributeKeyExists(inID: Long): Boolean = doesThisExist("SELECT count(1) from QuantityAttribute where id=" + inID)

  def textAttributeKeyExists(inID: Long): Boolean = doesThisExist("SELECT count(1) from TextAttribute where id=" + inID)

  def dateAttributeKeyExists(inID: Long): Boolean = doesThisExist("SELECT count(1) from DateAttribute where id=" + inID)

  def booleanAttributeKeyExists(inID: Long): Boolean = doesThisExist("SELECT count(1) from BooleanAttribute where id=" + inID)

  def fileAttributeKeyExists(inID: Long): Boolean = doesThisExist("SELECT count(1) from FileAttribute where id=" + inID)

  /** Excludes those entities that are really relationtypes, attribute types, or quantity units. */
  def entityOnlyKeyExists(inID: Long): Boolean = {
    doesThisExist("SELECT count(1) from Entity where (not archived) and id=" + inID + " and id in (select id from entity " + limitToEntitiesOnly
                                                                                                                             (ENTITY_ONLY_SELECT_PART) + ")")
  }

  def entityKeyExists(inID: Long): Boolean = doesThisExist("SELECT count(1) from Entity where id=" + inID)

  def sortingIndexInUse(groupIdIn: Long, sortingIndexIn: Long): Boolean = doesThisExist("SELECT count(1) from Entitiesinagroup where group_id=" +
                                                                                        groupIdIn + " and sorting_index=" + sortingIndexIn)

  def classKeyExists(inID: Long): Boolean = doesThisExist("SELECT count(1) from class where id=" + inID)

  def relationTypeKeyExists(inId: Long): Boolean = doesThisExist("SELECT count(1) from RelationType where entity_id=" + inId)

  def relationToEntityKeyExists(inRelTypeId: Long, inEntityId1: Long, inEntityId2: Long): Boolean = {
    doesThisExist("SELECT count(1) from RelationToEntity where rel_type_id=" + inRelTypeId + " and entity_id_1=" + inEntityId1 + " and entity_id_2=" +
                  inEntityId2)
  }

  def groupKeyExists(inId: Long): Boolean = {
    doesThisExist("SELECT count(1) from grupo where id=" + inId)
  }

  def relationToGroupKeyExists(entityId: Long, relTypeId: Long, groupId: Long): Boolean = {
    doesThisExist("SELECT count(1) from RelationToGroup where entity_id=" + entityId + " and rel_type_id=" + relTypeId + " and group_id=" + groupId)
  }

  // where we create the table also calls this.
  // Longer than the old 60 (needed), and a likely familiar length to many people (for ease in knowing when done), seems a decent balance. If any longer
  // is needed, maybe it should be put in a TextAttribute and make those more convenient to use, instead.
  def entityNameLength: Int = 160

  // in postgres, one table "extends" the other (see comments in createTables)
  def relationTypeNameLength: Int = entityNameLength

  def classNameLength: Int = entityNameLength

  /**
   * Allows querying for a range of objects in the database; returns a java.util.Map with keys and names.
   * 1st parm is index to start with (0-based), 2nd parm is # of obj's to return (if None, means no limit).
   */
  def getEntities(inStartingObjectIndex: Long, inMaxVals: Option[Long] = None): java.util.ArrayList[Entity] = {
    getEntitiesGeneric(inStartingObjectIndex, inMaxVals, "Entity")
  }

  /** Excludes those entities that are really relationtypes, attribute types, or quantity units. Otherwise similar to getEntities.
    *
    * *****NOTE*****: The limitByClass:Boolean parameter is not redundant with the inClassId: inClassId could be None and we could still want
    * to select only those entities whose class_id is NULL, such as when enforcing group uniformity (see method hasMixedClasses and its
    * uses, for more info).
    *
    * The parameter omitEntity is (at this writing) used for the id of a class-defining entity, which we shouldn't show for editing when showing all the
    * entities in the class (editing that is a separate menu option), otherwise it confuses things.
    * */
  def getEntitiesOnly(inStartingObjectIndex: Long, inMaxVals: Option[Long] = None, inClassId: Option[Long] = None,
                      limitByClass: Boolean = false, classDefiningEntity: Option[Long] = None,
                      groupToOmitIdIn: Option[Long] = None): java.util.ArrayList[Entity] = {
    getEntitiesGeneric(inStartingObjectIndex, inMaxVals, "EntityOnly", inClassId, limitByClass, classDefiningEntity, groupToOmitIdIn)
  }

  /** similar to getEntities */
  def getRelationTypes(inStartingObjectIndex: Long, inMaxVals: Option[Long] = None): java.util.ArrayList[Entity] = {
    getEntitiesGeneric(inStartingObjectIndex, inMaxVals, "RelationType")
  }

  def getMatchingEntities(inStartingObjectIndex: Long, inMaxVals: Option[Long] = None, omitEntityIdIn: Option[Long],
                          nameRegexIn: String): java.util.ArrayList[Entity] = {
    val omissionExpression: String = if (omitEntityIdIn.isEmpty) "true" else "(not id=" + omitEntityIdIn.get + ")"
    val sql: String = s"select id, name, public, class_id from entity where not archived and name ~* '$nameRegexIn'" +
                      " and " + omissionExpression + " order by id limit " + checkIfShouldBeAllResults(inMaxVals) + " offset " + inStartingObjectIndex
    val earlyResults = dbQuery(sql, "Long,String,Boolean,Long")
    val finalResults = new java.util.ArrayList[Entity]
    // idea: (see getEntitiesGeneric for idea, see if applies here)
    for (result <- earlyResults) {
      // None of these values should be of "None" type, so not checking for that. If they are it's a bug:
      finalResults.add(new Entity(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[String], result(2).asInstanceOf[Option[Boolean]],
                                  result(3).asInstanceOf[Option[Long]]))
    }
    require(finalResults.size == earlyResults.size)
    finalResults
  }

  def getMatchingGroups(inStartingObjectIndex: Long, inMaxVals: Option[Long] = None, omitGroupIdIn: Option[Long],
                        nameRegexIn: String): java.util.ArrayList[Group] = {
    val omissionExpression: String = if (omitGroupIdIn.isEmpty) "true" else "(not id=" + omitGroupIdIn.get + ")"
    val sql: String = s"select id, name, insertion_date, allow_mixed_classes from grupo where name ~* '$nameRegexIn'" +
                      " and " + omissionExpression + " order by id limit " + checkIfShouldBeAllResults(inMaxVals) + " offset " + inStartingObjectIndex
    val earlyResults = dbQuery(sql, "Long,String,Long,Boolean")
    val finalResults = new java.util.ArrayList[Group]
    // idea: (see getEntitiesGeneric for idea, see if applies here)
    for (result <- earlyResults) {
      // None of these values should be of "None" type, so not checking for that. If they are it's a bug:
      finalResults.add(new Group(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[String], result(2).get.asInstanceOf[Long],
                                 result(3).get.asInstanceOf[Boolean]))
    }
    require(finalResults.size == earlyResults.size)
    finalResults
  }

  def getContainingEntities1(entityIn: Entity, startingIndexIn: Long, maxValsIn: Option[Long] = None): java.util.ArrayList[(Long, Entity)] = {
    val sql: String = "select rel_type_id, entity_id_1 from relationtoentity where entity_id_2=" + entityIn.getId + " order by entity_id_1 limit " +
                      checkIfShouldBeAllResults(maxValsIn) + " offset " + startingIndexIn
    //note: this should be changed when we update relation stuff similarly, to go both ways in the relation (either entity_id_1 or
    // 2: helpfully returned; & in UI?)
    getContainingEntities_helper(sql)
  }

  def getContainingEntities_helper(sqlIn: String): java.util.ArrayList[(Long, Entity)] = {
    val earlyResults = dbQuery(sqlIn, "Long,Long")
    val finalResults = new java.util.ArrayList[(Long, Entity)]
    // idea: should the remainder of this method be moved to Entity, so the persistence layer doesn't know anything about the Model? (helps avoid circular
    // dependencies? is a cleaner design?.)
    for (result <- earlyResults) {
      // None of these values should be of "None" type, so not checking for that. If they are it's a bug:
      val rel_type_id: Long = result(0).get.asInstanceOf[Long]
      val entity: Entity = new Entity(this, result(1).get.asInstanceOf[Long])
      finalResults.add((rel_type_id, entity))
    }

    require(finalResults.size == earlyResults.size)
    finalResults
  }

  def getContainingRelationToGroups(entityIn: Entity, startingIndexIn: Long, maxValsIn: Option[Long] = None): java.util.ArrayList[RelationToGroup] = {
    // BUG (tracked in tasks): there is a disconnect here between this method and its _helper method, because one uses the eig table, the other the rtg table,
    // and there is no requirement/enforcement that all groups defined in eig are in an rtg, so they could get dif't/unexpected results.
    // So, could: see the expectation of the place(s) calling this method, if uniform, make these 2 methods more uniform in what they do in meeting that,
    // OR, could consider whether we really should have an enforcement between the 2 tables...?
    // THIS BUg currently prevents searching for then deleting the entity w/ this in name: "OTHER ENTITY NOTED IN A DELETION BUG" (see also other issue
    // in Controller.java where that same name is mentioned. Related, be cause in that case on the line:
    //    "descriptions = descriptions.substring(0, descriptions.length - delimiter.length) + ".  ""
    // ...one gets the below exception throw, probably for the same or related reason:
        /*
        ==============================================
        **CURRENT ENTITY:while at it, order a valentine's card on amazon asap (or did w/ cmas shopping?)
        No attributes have been assigned to this object, yet.
        1-Add attribute (quantity, true/false, date, text, external file, relation to entity or group: i.e., ownership of or "has" another entity, family ties, etc)...
        2-Import/Export...
        3-Edit name
        4-Delete or Archive...
        5-Go to...
        6-List next items
        7-Set current entity (while at it, order a valentine's card on amazon asap (or did w/ cmas shopping?)) as default (first to come up when launching this program.)
        8-Edit public/nonpublic status
        0/ESC - back/previous menu
        4






        ==============================================
        Choose a deletion or archiving option:
        1-Delete this entity
                 2-Archive this entity (remove from visibility but not permanent/total deletion)
        0/ESC - back/previous menu
        1
        An error occurred: "java.lang.StringIndexOutOfBoundsException: String index out of range: -2".  If you can provide simple instructions to reproduce it consistently, maybe it can be fixed.  Do you want to see the detailed output? (y/n):
          y






        ==============================================
        java.lang.StringIndexOutOfBoundsException: String index out of range: -2
        at java.lang.String.substring(String.java:1911)
        at org.onemodel.controller.Controller.deleteOrArchiveEntity(Controller.scala:644)
        at org.onemodel.controller.EntityMenu.entityMenu(EntityMenu.scala:232)
        at org.onemodel.controller.EntityMenu.entityMenu(EntityMenu.scala:388)
        at org.onemodel.controller.Controller.showInEntityMenuThenMainMenu(Controller.scala:277)
        at org.onemodel.controller.MainMenu.mainMenu(MainMenu.scala:80)
        at org.onemodel.controller.MainMenu.mainMenu(MainMenu.scala:98)
        at org.onemodel.controller.MainMenu.mainMenu(MainMenu.scala:98)
        at org.onemodel.controller.Controller.menuLoop$1(Controller.scala:140)
        at org.onemodel.controller.Controller.start(Controller.scala:143)
        at org.onemodel.TextUI.launchUI(TextUI.scala:220)
        at org.onemodel.TextUI$.main(TextUI.scala:34)
        at org.onemodel.TextUI.main(TextUI.scala:1)
        */

    val sql: String = "select group_id from entitiesinagroup where entity_id=" + entityIn.getId + " order by group_id limit " +
                      checkIfShouldBeAllResults(maxValsIn) + " offset " + startingIndexIn
    getContainingRelationToGroups_helper(sql)
  }

  def getContainingRelationToGroups_helper(sqlIn: String): java.util.ArrayList[RelationToGroup] = {
    val earlyResults = dbQuery(sqlIn, "Long")
    val groupIdResults = new java.util.ArrayList[Long]
    // idea: should the remainder of this method be moved to Group, so the persistence layer doesn't know anything about the Model? (helps avoid circular
    // dependencies? is a cleaner design?)
    for (result <- earlyResults) {
      //val group:Group = new Group(this, result(0).asInstanceOf[Long])
      groupIdResults.add(result(0).get.asInstanceOf[Long])
    }
    require(groupIdResults.size == earlyResults.size)
    val containingRelationToGroups: java.util.ArrayList[RelationToGroup] = new java.util.ArrayList[RelationToGroup]
    for (gid <- groupIdResults.toArray) {
      val rtgs = getRelationToGroupsByGroup(gid.asInstanceOf[Long], 0)
      for (rtg <- rtgs.toArray) containingRelationToGroups.add(rtg.asInstanceOf[RelationToGroup])
    }
    containingRelationToGroups
  }

  // (why does compiler not like both of these to have same name, given the different parameter types?)
  def getContainingEntities2(relationToGroupIn: RelationToGroup, startingIndexIn: Long, maxValsIn: Option[Long] = None): java.util.ArrayList[(Long, Entity)] = {
    val sql: String = "select rel_type_id, entity_id from relationtogroup where entity_id=" + relationToGroupIn.getParentId +
                      " and group_id=" + relationToGroupIn.getGroupId +
                      " order by entity_id, rel_type_id limit " +
                      checkIfShouldBeAllResults(maxValsIn) + " offset " + startingIndexIn
    //note: this should be changed when we update relation stuff similarly, to go both ways in the relation (either entity_id_1 or
    // 2: helpfully returned; & in UI?)
    getContainingEntities_helper(sql)
  }

  //idea: consider: do we want this? if so finish,revu,test:
  //(see similar comment in controller)
  //def getContainingRelationToGroups(relationToGroupIn:RelationToGroup, startingIndexIn:Long, maxValsIn:Option[Long]=None): java.util
  // .ArrayList[RelationToGroup] = {
  //  val sql: String = "select group_id from EntitiesInAGroup where entity_id=" + entityIdIn + " order by group_id limit " +
  //                    checkIfShouldBeAllResults(maxValsIn) + " offset " + startingIndexIn
  //  getContainingRelationToGroups_helper(sql)
  //}

  // 1st parm is 0-based index to start with, 2nd parm is # of obj's to return (if None, means no limit).
  private def getEntitiesGeneric(inStartingObjectIndex: Long, inMaxVals: Option[Long], inTableName: String,
                                 inClassId: Option[Long] = None, limitByClass: Boolean = false,
                                 classDefiningEntity: Option[Long] = None, groupToOmitIdIn: Option[Long] = None): java.util.ArrayList[Entity] = {
    val ENTITY_SELECT_PART: String = "SELECT e.id, e.name, e.public, e.class_id"
    val sql: String = ENTITY_SELECT_PART + (if (inTableName.compareToIgnoreCase("RelationType") == 0) ", r.name_in_reverse_direction, r.directionality "
    else "") +
                      " from Entity e " +
                      (if (inTableName.compareToIgnoreCase("RelationType") == 0) {
                        // for RelationTypes, hit both tables since one "inherits", but limit it to those rows
                        // for which a RelationType row also exists.
                        ", RelationType r "
                      } else "") +
                      " where (not archived) and true " +
                      classLimit(limitByClass, inClassId) +
                      (if (limitByClass && classDefiningEntity.isDefined) " and id != " + classDefiningEntity.get else "") +
                      (if (inTableName.compareToIgnoreCase("RelationType") == 0) {
                        // for RelationTypes, hit both tables since one "inherits", but limit it to those rows
                        // for which a RelationType row also exists.
                        " and e.id = r.entity_id "
                      } else "") +
                      (if (inTableName.compareToIgnoreCase("EntityOnly") == 0) limitToEntitiesOnly(ENTITY_SELECT_PART) else "") +
                      (if (groupToOmitIdIn.isDefined) " except (" + ENTITY_SELECT_PART + " from entity e, " +
                                                    "EntitiesInAGroup eiag where e.id=eiag.entity_id and " +
                                                    "group_id=" + groupToOmitIdIn.get + ")"
                      else "") +
                      " order by id limit " + checkIfShouldBeAllResults(inMaxVals) + " offset " + inStartingObjectIndex
    val earlyResults = dbQuery(sql,
                               if (inTableName.compareToIgnoreCase("RelationType") == 0) "Long,String,Boolean,Long,String,String"
                               else "Long,String,Boolean,Long")
    val finalResults = new java.util.ArrayList[Entity]
    // idea: should the remainder of this method be moved to Entity, so the persistence layer doesn't know anything about the Model? (helps avoid circular
    // dependencies; is a cleaner design.)
    for (result <- earlyResults) {
      // None of these values should be of "None" type, so not checking for that. If they are it's a bug:
      if (inTableName.compareToIgnoreCase("RelationType") == 0) {
        finalResults.add(new RelationType(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[String], result(4).get.asInstanceOf[String],
                                          result(5).get.asInstanceOf[String]))
      } else {
        finalResults.add(new Entity(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[String], result(2).asInstanceOf[Option[Boolean]],
                                    result(3).asInstanceOf[Option[Long]]))
      }
    }

    require(finalResults.size == earlyResults.size)
    finalResults
  }

  /** Allows querying for a range of objects in the database; returns a java.util.Map with keys and names.
    * 1st parm is index to start with (0-based), 2nd parm is # of obj's to return (if None, means no limit).
    */
  def getGroups(inStartingObjectIndex: Long, inMaxVals: Option[Long] = None, groupToOmitIdIn: Option[Long] = None): java.util.ArrayList[Group] = {
    val sql = "SELECT id, name, insertion_date, allow_mixed_classes from grupo " +
              " order by id limit " + checkIfShouldBeAllResults(inMaxVals) + " offset " + inStartingObjectIndex
    val earlyResults = dbQuery(sql, "Long,String,Long,Boolean")
    val finalResults = new java.util.ArrayList[Group]
    // idea: should the remainder of this method be moved to RTG, so the persistence layer doesn't know anything about the Model? (helps avoid circular
    // dependencies; is a cleaner design.)
    for (result <- earlyResults) {
      // None of these values should be of "None" type, so not checking for that. If they are it's a bug:
      finalResults.add(new Group(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[String], result(2).get.asInstanceOf[Long],
                                 result(3).get.asInstanceOf[Boolean]))
    }
    require(finalResults.size == earlyResults.size)
    finalResults
  }


  def getClasses(inStartingObjectIndex: Long, inMaxVals: Option[Long] = None): java.util.ArrayList[EntityClass] = {
    val sql: String = "SELECT id, name, defining_entity_id from class order by id limit " +
                      checkIfShouldBeAllResults(inMaxVals) + " offset " + inStartingObjectIndex
    val earlyResults = dbQuery(sql, "Long,String,Long")
    val finalResults = new java.util.ArrayList[EntityClass]
    // idea: should the remainder of this method be moved to EntityClass, so the persistence layer doesn't know anything about the Model? (helps avoid circular
    // dependencies; is a cleaner design; see similar comment in getEntitiesGeneric.)
    for (result <- earlyResults) {
      // None of these values should be of "None" type, so not checking for that. If they are it's a bug:
      finalResults.add(new EntityClass(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[String], result(2).get.asInstanceOf[Long]))
    }
    require(finalResults.size == earlyResults.size)
    finalResults
  }

  private def checkIfShouldBeAllResults(inMaxVals: Option[Long]): String = if (inMaxVals.isEmpty) "ALL" else inMaxVals.get.toString

  def getGroupEntriesData(groupIdIn: Long, limitIn: Option[Long] = None): List[Array[Option[Any]]] = {
    val results = dbQuery(// LIKE THE OTHER 2 BELOW SIMILAR METHODS:
                          // Need to make sure it gets the desired rows, rather than just some, so the order etc matters at each step, probably.
                          // idea: needs automated tests (in task list also).
                          "select eiag.entity_id, eiag.sorting_index from entity e, entitiesinagroup eiag where e.id=eiag.entity_id and (not e.archived)" +
                          " and eiag.group_id=" + groupIdIn +
                          " order by eiag.sorting_index, eiag.entity_id limit " + checkIfShouldBeAllResults(limitIn),
                          "Long,Long")
    results
  }

  /** As of 2014-8-4, this is only called when calculating a new sorting_index, but if it were used for something else ever, one might consider whether
    * to (optionally!) add back the code removed today which ignores archived entries.  We can't ignore them for getting a new sorting_index: bug.
    */
  def getAdjacentGroupEntries(groupIdIn: Long, sortingIndexIn: Long, limitIn: Option[Long] = None, forwardNotBackIn: Boolean): List[Array[Option[Any]]] = {
    val results = dbQuery("select eiag.entity_id, eiag.sorting_index from entity e, entitiesinagroup eiag where e.id=eiag.entity_id and (not e.archived)" +
                          " and eiag.group_id=" + groupIdIn + " and eiag.sorting_index " +
                          (if (forwardNotBackIn) ">" else "<") + sortingIndexIn +
                          " order by eiag.sorting_index " + (if (forwardNotBackIn) "ASC" else "DESC") + ", " +
                          "eiag.entity_id limit " + checkIfShouldBeAllResults(limitIn),
                          "Long,Long")
    results
  }

  /** This one should explicitly NOT omit archived entities (unless parameterized for that later). See caller's comments for more, on purpose.
    */
  def getNearestGroupEntry(groupIdIn: Long, startingPointSortingIndexIn: Long, /* farNewNeighborSortingIndexIn: Long,*/
                           forwardNotBackIn: Boolean): Option[Long] = {
    val results = dbQuery("select sorting_index from entitiesinagroup where group_id=" + groupIdIn + " and sorting_index " +
                          (if (forwardNotBackIn) ">" else "<") + startingPointSortingIndexIn +
                          " order by sorting_index " + (if (forwardNotBackIn) "ASC" else "DESC") +
                          " limit 1",
                          "Long")
    if (results.isEmpty) {
      None
    } else {
      if (results.size > 1) throw new OmDatabaseException("Probably the caller didn't expect this to get >1 results...Is that even meaningful?")
      else results.head(0).asInstanceOf[Option[Long]]
    }
  }

  // 2nd parm is 0-based index to start with, 3rd parm is # of obj's to return (if < 1 then it means "all"):
  def getGroupEntryObjects(inGroupId: Long, inStartingObjectIndex: Long, inMaxVals: Option[Long] = None): java.util.ArrayList[Entity] = {
    val sql = "select entity_id, sorting_index from entity e, EntitiesInAGroup eiag where e.id=eiag.entity_id and (not e.archived) " +
              " and eiag.group_id=" + inGroupId +
              " order by eiag.sorting_index, eiag.entity_id limit " + checkIfShouldBeAllResults(inMaxVals) + " offset " + inStartingObjectIndex
    val earlyResults = dbQuery(sql, "Long,Long")
    val finalResults = new java.util.ArrayList[Entity]
    // idea: should the remainder of this method be moved to Entity, so the persistence layer doesn't know anything about the Model? (helps avoid circular
    // dependencies; is a cleaner design. Or, maybe this class and all the object classes like Entity, etc, are all part of the same layer.)
    for (result <- earlyResults) {
      // None of these values should be of "None" type, so not checking for that. If they are it's a bug:
      finalResults.add(new Entity(this, result(0).get.asInstanceOf[Long]))
    }
    require(finalResults.size == earlyResults.size)
    finalResults
  }

  private def limitToEntitiesOnly(selectColumnNames: String): String = {
    val sql: StringBuilder = new StringBuilder
    sql.append("except (").append(selectColumnNames).append(" from entity e, quantityattribute q where e.id=q.unit_id) ")
    sql.append("except (").append(selectColumnNames).append(" from entity e, quantityattribute q where e.id=q.attr_type_id) ")
    sql.append("except (").append(selectColumnNames).append(" from entity e, textattribute t where e.id=t.attr_type_id) ")
    sql.append("except (").append(selectColumnNames).append(" from entity e, relationtype t where e.id=t.entity_id) ")
    sql.append("except (").append(selectColumnNames).append(" from entity e, dateattribute t where e.id=t.attr_type_id) ")
    sql.append("except (").append(selectColumnNames).append(" from entity e, booleanattribute t where e.id=t.attr_type_id) ")
    sql.append("except (").append(selectColumnNames).append(" from entity e, fileattribute t where e.id=t.attr_type_id) ")
    sql.toString()
  }

  def getEntityData(inID: Long): Array[Option[Any]] = {
    dbQueryWrapperForOneRow("SELECT name, class_id, public from Entity where (not archived) and id=" + inID, "String,Long,Boolean")
  }

  def getEntityName(inID: Long): Option[String] = {
    val name: Option[Any] = getEntityData(inID)(0)
    if (name.isEmpty) None
    else name.asInstanceOf[Option[String]]
  }

  def getClassData(inID: Long): Array[Option[Any]] = {
    dbQueryWrapperForOneRow("SELECT name, defining_entity_id from class where id=" + inID, "String,Long")
  }

  def getClassName(inID: Long): Option[String] = {
    val name: Option[Any] = getClassData(inID)(0)
    if (name.isEmpty) None
    else name.asInstanceOf[Option[String]]
  }

  /** returns the Attributes, and a Long indicating the total # that could be returned with infinite display space (total existing).
    * The parameter maxValsIn can be 0 for 'all'.
    */
  def getSortedAttributes(inID: Long, inStartingObjectIndex: Long, maxValsIn: Long): (java.util.ArrayList[Attribute], Long) = {
    val retval: java.util.ArrayList[Attribute] = new java.util.ArrayList[Attribute]
    // First select the counts from each table, keep a running total so we know when to select attributes (compared to inStartingObjectIndex)
    // and when to stop.
    val tables: Array[String] = Array("QuantityAttribute", "BooleanAttribute", "DateAttribute", "TextAttribute", "FileAttribute", "RelationToEntity",
                                      "RelationToGroup")
    val columnsSelectedByTable: Array[String] = Array("id,parent_id,attr_type_id,unit_id,quantity_number,valid_on_date,observation_date",
                                                      "id,parent_id,attr_type_id,booleanValue,valid_on_date,observation_date",
                                                      "id,parent_id,attr_type_id,date",
                                                      "id,parent_id,attr_type_id,textValue,valid_on_date,observation_date",

                                                      "id,parent_id,attr_type_id,description,original_file_date,stored_date,original_file_path,readable," +
                                                      "writable,executable,size,md5hash",

                                                      "rel_type_id,entity_id_1,entity_id_2,valid_on_date,observation_date",
                                                      "entity_id,rel_type_id,group_id,valid_on_date,observation_date")
    val typesByTable: Array[String] = Array("Long,Long,Long,Long,Float,Long,Long",
                                            "Long,Long,Long,Boolean,Long,Long",
                                            "Long,Long,Long,Long",
                                            "Long,Long,Long,String,Long,Long",
                                            "Long,Long,Long,String,Long,Long,String,Boolean,Boolean,Boolean,Long,String",
                                            "Long,Long,Long,Long,Long",
                                            "Long,Long,Long,Long,Long")
    val whereClausesByTable: Array[String] = Array(tables(0) + ".parent_id=" + inID,
                                                   tables(1) + ".parent_id=" + inID,
                                                   tables(2) + ".parent_id=" + inID,
                                                   tables(3) + ".parent_id=" + inID,
                                                   tables(4) + ".parent_id=" + inID,
                                                   tables(5) + ".entity_id_1=" + inID,
                                                   tables(6) + ".entity_id=" + inID)
    val orderByClausesByTable: Array[String] = Array("id", "id", "id", "id", "id", "entity_id_1", "group_id")

    // first just get a total row count for UI convenience later (to show how many left not viewed yet)
    var totalRowsAvailable: Long = 0
    var tableIndexForRowCounting = 0
    while ((maxValsIn == 0 || totalRowsAvailable <= maxValsIn) && tableIndexForRowCounting < tables.length) {
      val tableName = tables(tableIndexForRowCounting)
      totalRowsAvailable += extractRowCountFromCountQuery("select count(*) from " + tableName + " where " + whereClausesByTable(tableIndexForRowCounting))
      tableIndexForRowCounting += 1
    }

    // idea: this could change to a val and be filled w/ a recursive helper method; other vars might go away then too.
    var tableListIndex: Int = 0
    //keeps track of where we are in getting rows >= inStartingObjectIndex and <= maxValsIn
    var counter: Long = 0
    while ((maxValsIn == 0 || counter - inStartingObjectIndex <= maxValsIn) && tableListIndex < tables.length) {
      val tableName = tables(tableListIndex)
      val thisTablesRowCount: Long = extractRowCountFromCountQuery("select count(*) from " + tableName + " where " + whereClausesByTable(tableListIndex))
      if (thisTablesRowCount > 0 && counter + thisTablesRowCount >= inStartingObjectIndex) {
        try {
          // idea: could speed this query up in part? by doing on each query something like:
          //       limit maxValsIn+" offset "+ inStartingObjectIndex-counter;
          // ..and then incrementing the counters appropriately.
          val key = whereClausesByTable(tableListIndex).substring(0, whereClausesByTable(tableListIndex).indexOf("="))
          val columns = tableName + "." + columnsSelectedByTable(tableListIndex).replace(",", "," + tableName + ".")
          var sql: String = "select " + columns + " from " + tableName + ", " +
                            "entity " + " where (not entity.archived) and entity.id=" + key + " and " +
                            whereClausesByTable(tableListIndex)
          if (tableName.toLowerCase == "relationtoentity") {
            sql += " and not exists(select 1 from entity e2, relationtoentity rte2 where e2.id=rte2.entity_id_2 and relationtoentity.entity_id_2=rte2" +
                   ".entity_id_2 and e2.archived)"
          }
          sql += " order by " + orderByClausesByTable(tableListIndex)
          val results = dbQuery(sql, typesByTable(tableListIndex))
          for (result: Array[Option[Any]] <- results) {
            // skip past those that are outside the range to retrieve
            //idea: use some better scala/function construct here so we don't keep looping after counter hits the max (and to make it cleaner)?
            //idea: move it to the same layer of code that has the Attribute classes?
            // Don't get it if it's not in the requested range:
            if (counter >= inStartingObjectIndex && (maxValsIn == 0 || counter <= inStartingObjectIndex + maxValsIn)) {
              if (tableName == "QuantityAttribute") {
                retval.add(new QuantityAttribute(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[Long], result(2).get.asInstanceOf[Long],
                                                 result(3).get.asInstanceOf[Long], result(4).get.asInstanceOf[Float],
                                                 if (result(5).isEmpty) None else Some(result(5).get.asInstanceOf[Long]), result(6).get.asInstanceOf[Long]))
              } else if (tableName == "TextAttribute") {
                retval.add(new TextAttribute(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[Long], result(2).get.asInstanceOf[Long],
                                             result(3).get.asInstanceOf[String], if (result(4).isEmpty) None else Some(result(4).get.asInstanceOf[Long]),
                                             result(5).get.asInstanceOf[Long]))
              } else if (tableName == "DateAttribute") {
                retval.add(new DateAttribute(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[Long], result(2).get.asInstanceOf[Long],
                                             result(3).get.asInstanceOf[Long]))
              } else if (tableName == "BooleanAttribute") {
                retval.add(new BooleanAttribute(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[Long], result(2).get.asInstanceOf[Long],
                                                result(3).get.asInstanceOf[Boolean], if (result(4).isEmpty) None else Some(result(4).get.asInstanceOf[Long]),
                                                result(5).get.asInstanceOf[Long]))
              } else if (tableName == "FileAttribute") {
                retval.add(new FileAttribute(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[Long], result(2).get.asInstanceOf[Long],
                                             result(3).get.asInstanceOf[String], result(4).get.asInstanceOf[Long], result(5).get.asInstanceOf[Long],
                                             result(6).get.asInstanceOf[String], result(7).get.asInstanceOf[Boolean], result(8).get.asInstanceOf[Boolean],
                                             result(9).get.asInstanceOf[Boolean], result(10).get.asInstanceOf[Long], result(11).get.asInstanceOf[String]))
              } else if (tableName == "RelationToEntity") {
                retval.add(new RelationToEntity(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[Long], result(2).get.asInstanceOf[Long],
                                                if (result(3).isEmpty) None else Some(result(3).get.asInstanceOf[Long]), result(4).get.asInstanceOf[Long]))
              } else if (tableName == "RelationToGroup") {
                retval.add(new RelationToGroup(this, result(0).get.asInstanceOf[Long], result(1).get.asInstanceOf[Long], result(2).get.asInstanceOf[Long],
                                               if (result(3).isEmpty) None else Some(result(3).get.asInstanceOf[Long]),
                                               result(4).get.asInstanceOf[Long]))
              } else throw new Exception("invalid table type?: '" + tableName + "'")
            }
            counter += 1
          }
        }
      } else {
        counter += thisTablesRowCount
      }
      tableListIndex += 1
    }
    (retval, totalRowsAvailable)
  }

  /** The 2nd parameter is to avoid saying an entity is a duplicate of itself: checks for all others only. */
  def isDuplicateEntity(inName: String, inSelfIdToIgnore: Option[Long] = None): Boolean = {
    isDuplicateRow(inName, "entity", "id", "name", Some("(not archived)"), inSelfIdToIgnore) ||
    isDuplicateRow(inName, "relationtype", "entity_id", "name_in_reverse_direction", None, inSelfIdToIgnore)
  }

  ///** The inSelfIdToIgnore parameter is to avoid saying a class is a duplicate of itself: checks for all others only. */
  def isDuplicateRow(possibleDuplicateIn: String, table: String, keyColumnToIgnoreOn: String, columnToCheckForDupValues: String, extraCondition: Option[String],
                     inSelfIdToIgnore: Option[Long] = None): Boolean = {
    val valueToCheck: String = escapeQuotesEtc(possibleDuplicateIn)

    val exception: String =
      if (inSelfIdToIgnore.isEmpty) ""
      else "and not " + keyColumnToIgnoreOn + "=" + inSelfIdToIgnore.get.toString

    doesThisExist("SELECT count(" + keyColumnToIgnoreOn + ") from " + table + " where " +
                  (if (extraCondition.isDefined && extraCondition.get.nonEmpty) extraCondition.get else "true") +
                  " and lower(" + columnToCheckForDupValues + ")=lower('" + valueToCheck + "') " + exception,
                  failIfMoreThanOneIn = false)
  }


  /** The 2nd parameter is to avoid saying a class is a duplicate of itself: checks for all others only. */
  def isDuplicateClass(inName: String, inSelfIdToIgnore: Option[Long] = None): Boolean = {
    isDuplicateRow(inName, "class", "id", "name", None, inSelfIdToIgnore)
  }

  /**
   * Like jdbc's default, if you don't call begin/rollback/commit, it will commit after every stmt,
   * using the default behavior of jdbc; but if you call begin/rollback/commit, it will let you manage
   * explicitly and will automatically turn autocommit on/off as needed to allow that.
   */
  def beginTrans() {
    // implicitly begins a transaction, according to jdbc documentation
    mConn.setAutoCommit(false)
  }

  def rollbackTrans() {
    mConn.rollback()
    // so future work is auto- committed unless programmer explicitly opens another transaction
    mConn.setAutoCommit(true)
  }

  def commitTrans() {
    mConn.commit()
    // so future work is auto- committed unless programmer explicitly opens another transaction
    mConn.setAutoCommit(true)
  }

  def maxIdValue: Long = {
    // Max size for a Java long type, and for a postgresql 7.2.1 bigint type (which is being used, at the moment, for the id value in Entity table.
    // (these values are from file:///usr/share/doc/postgresql-doc-9.1/html/datatype-numeric.html)
    9223372036854775807L
  }

  def minIdValue: Long = {
    -9223372036854775808L
  }

  protected override def finalize() {
    super.finalize()
    if (mConn != null) mConn.close()
  }

  def extractRowCountFromCountQuery(inSQL: String): Long = {
    val results = dbQueryWrapperForOneRow(inSQL, "Long")
    // not checking for None here as its presence would be a bug:
    val result: Long = results(0).get.asInstanceOf[Long]
    result
  }

  /** Convenience function. Error message it gives if > 1 found assumes that sql passed in will return only 1 row! */
  private def doesThisExist(inSql: String, failIfMoreThanOneIn: Boolean = true): Boolean = {
    val rowcnt: Long = extractRowCountFromCountQuery(inSql)
    if (failIfMoreThanOneIn) {
      if (rowcnt == 1) true
      else if (rowcnt > 1) throw new Exception("Should there be > 1 entries for sql: " + inSql + "?? (" + rowcnt + " were found.)")
      else false
    }
    else rowcnt >= 1
  }

  /** Cloned to deleteObjects: CONSIDER UPDATING BOTH if updating one.  Returns the # of rows deleted.
    * Unless the parameter rowsExpected==-1, it will allow any # of rows to be deleted; otherwise if the # of rows is wrong it will abort tran & fail.
    */
  private def deleteObjects(tableNameIn: String, inWhereClause: String, rowsExpected: Long = 1, callerManagesTransactions: Boolean = false): Long = {
    //idea: enhance this to also check & return the # of rows deleted, to the caller to just make sure? If so would have to let caller handle transactions.
    //idea: learn to eliminate this var (and others in this file) in a scala-like way
    val sql = "DELETE FROM " + tableNameIn + " " + inWhereClause
    if (!callerManagesTransactions) beginTrans()
    try {
      val rowsDeleted = dbAction(sql, callerChecksRowCountEtc = true)
      if (rowsExpected >= 0 && rowsDeleted != rowsExpected) {
        // Roll back, as we definitely don't want to delete an unexpected # of rows.
        // Do it ***EVEN THOUGH callerManagesTransaction IS true***: seems cleaner/safer this way.
        rollbackTrans()
        throw new Exception("Delete command would have removed " + rowsDeleted + "rows, but " + rowsExpected + " were expected! Did not perform delete.")
      } else {
        if (!callerManagesTransactions) commitTrans()
        rowsDeleted
      }
    } catch {
      case e: Exception => throw rollbackWithCatch(e)
    }
  }

  /** Cloned from deleteObjects: CONSIDER UPDATING BOTH if updating one.
    */
  private def archiveObjects(tableNameIn: String, whereClauseIn: String, rowsExpected: Long = 1, callerManagesTransactions: Boolean = false) {
    //idea: enhance this to also check & return the # of rows deleted, to the caller to just make sure? If so would have to let caller handle transactions.
    //idea: learn to eliminate this var (and others in this file) in a scala-like way
    if (!callerManagesTransactions) beginTrans()
    try {
      val rowsAffected = dbAction("update " + tableNameIn + " set (archived, archived_date) = (true, " + System.currentTimeMillis() + ") " + whereClauseIn)
      if (rowsExpected >= 0 && rowsAffected != rowsExpected) {
        // Roll back, as we definitely don't want to affect an unexpected # of rows.
        // Do it ***EVEN THOUGH callerManagesTransaction IS true***: seems cleaner/safer this way.
        rollbackTrans()
        throw new Exception("Archive command would have updated " + rowsAffected + "rows, but " + rowsExpected + " were expected! Did not perform archive.")
      } else {
        if (!callerManagesTransactions) commitTrans()
      }
    } catch {
      case e: Exception => throw rollbackWithCatch(e)
    }
  }

  private def deleteObjectById(inTableName: String, inID: Long, callerManagesTransactions: Boolean = false): Unit = {
    deleteObjects(inTableName, "where id=" + inID, callerManagesTransactions = callerManagesTransactions)
  }

  /**
   * Although the next sequence value would be set automatically as the default for a column (at least the
   * way I have them defined so far in postgresql); we do it explicitly
   * so we know what sequence value to return, and what the unique key is of the row we just created!
   */
  private def getNewKey(inSequenceName: String): /*id*/ Long = {
    val result: Long = dbQueryWrapperForOneRow("SELECT nextval('" + inSequenceName + "')", "Long")(0).get.asInstanceOf[Long]
    result
  }

  // (idea: find out: why doesn't compiler (ide or cli) complain when the 'override' is removed from next line?)
  // idea: see comment on findUnusedSortingIndex
  def findIdWhichIsNotKeyOfAnyEntity: Long = {
    //better idea?  This should be fast because we start in remote regions and return as soon as an unused id is found, probably
    //only one iteration, ever.
    val startingId: Long = maxIdValue - 1

    @tailrec def findIdWhichIsNotKeyOfAnyEntity_helper(workingId: Long, counter: Long): Long = {
      if (entityKeyExists(workingId)) {
        if (workingId == maxIdValue) {
          // means we did a full loop across all possible ids!?  Doubtful. Probably would turn into a performance problem long before. It's a bug.
          throw new Exception("No id found which is not a key of any entity in the system. How could all id's be used??")
        }
        // idea: this check assumes that the thing to get IDs will re-use deleted ones and wrap around the set of #'s. That fix is on the list (informally
        // at this writing, 2013-11-18).
        if (counter > 1000) throw new Exception("Very unexpected, but could it be that you are running out of available entity IDs?? Have someone check, " +
                                                "before you need to create, for example, a thousand more entities.")
        findIdWhichIsNotKeyOfAnyEntity_helper(workingId - 1, counter + 1)
      } else workingId
    }

    findIdWhichIsNotKeyOfAnyEntity_helper(startingId, 0)
  }

}