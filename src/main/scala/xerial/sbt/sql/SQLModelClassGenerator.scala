package xerial.sbt.sql

import sbt._
import java.sql.{Connection, DriverManager, JDBCType, ResultSet}

import sbt.{File, IO}

case class Schema(columns: Seq[Column])
case class Column(name: String, reader:ColumnReader, sqlType: java.sql.JDBCType, isNullable: Boolean)

case class GeneratorConfig(sqlDir:File, targetDir:File)

class SQLModelClassGenerator(jdbcConfig: JDBCConfig) extends xerial.core.log.Logger {
  private val db = new JDBCClient(jdbcConfig)

  protected val typeMapping = SQLTypeMapping.default

  private def wrapWithLimit0(sql: String) = {
    s"""SELECT * FROM (
       |${sql}
       |)
       |LIMIT 0""".stripMargin
  }

  def checkResultSchema(sql: String): Schema = {
    db.withConnection {conn =>
      db.submitQuery(conn, sql) {rs =>
        val m = rs.getMetaData
        val cols = m.getColumnCount
        val colTypes = (1 to cols).map {i =>
          val name = m.getColumnName(i)
          val tpe = m.getColumnType(i)
          val jdbcType = JDBCType.valueOf(tpe)
          val reader = typeMapping(jdbcType)
          val nullable = m.isNullable(i) != 0
          Column(name, reader, jdbcType, nullable)
        }
        Schema(colTypes.toIndexedSeq)
      }
    }
  }

  def generate(config:GeneratorConfig) = {
    // Submit queries using multi-threads to minimize the waiting time
    for (sqlFile <- (config.sqlDir ** "*.sql").get.par) {
      val path = sqlFile.relativeTo(config.sqlDir).get.getPath
      val targetFile = config.targetDir / path
      val targetClassFile = file(targetFile.getPath.replaceAll("\\.sql$", ".scala"))
      info(s"Processing ${sqlFile}, target: ${targetFile}, ${targetClassFile}")
      IO.copyFile(sqlFile, targetFile)

      val sql = IO.read(sqlFile)
      val template = SQLTemplate(sql)
      val limit0 = wrapWithLimit0(template.populated)
      val schema = checkResultSchema(limit0)
      info(s"template:\n${template.noParam}")
      info(schema)

      val scalaCode = schemaToClass(sqlFile, config.sqlDir, schema)
      IO.write(targetClassFile, scalaCode)
    }
  }

  def schemaToClass(origFile: File, baseDir: File, schema: Schema): String = {
    val packageName = origFile.relativeTo(baseDir).map {f =>
      f.getParent.replaceAll("""[\\/]""", ".")
    }.getOrElse("")
    val name = origFile.getName.replaceAll("\\.sql$", "")

    val params = schema.columns.map {c =>
      s"${c.name}:${c.reader.name}"
    }

    val rsReader = schema.columns.zipWithIndex.map { case (c, i) =>
      s"rs.${c.reader.rsMethod}(${i+1})"
    }

    val code =
      s"""
         |package ${packageName}
         |import java.sql.ResultSet
         |
         |object class ${name} {
         |  def sql : String = "/${packageName.replaceAll("\\.", "/")}/${name}.sql"
         |  def read(rs:ResultSet) : ${name} = {
         |    ${name}(${rsReader.mkString(", ")})
         |  }
         |}
         |
         |case class ${name}(
         |  ${params.mkString(",\n  ")}
         |)
         |""".stripMargin

    info(code)
    code
  }

}
