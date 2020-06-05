package com.zyc.zdh

import java.sql.Timestamp
import java.util

import com.zyc.base.util.{DateUtil, JsonUtil}
import com.zyc.common.MariadbCommon
import com.zyc.zdh.datasources._
import org.apache.log4j.MDC
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.slf4j.LoggerFactory

object DataSources {

  val logger = LoggerFactory.getLogger(this.getClass)

  /**
    * 统一数据源处理入口
    *
    * @param spark
    * @param task_logs_id     任务记录id
    * @param dispatchOption   调度任务信息
    * @param inPut            输入数据源类型
    * @param inputOptions     输入数据源参数
    * @param inputCondition   输入数据源条件
    * @param inputCols        //输入字段
    * @param outPut           输出数据源类型
    * @param outputOptionions 输出数据源参数
    * @param outputCols       输出字段
    * @param sql              清空sql 语句
    */
  def DataHandler(spark: SparkSession, task_logs_id: String, dispatchOption: Map[String, Any], etlTaskInfo: Map[String, Any], inPut: String, inputOptions: Map[String, Any], inputCondition: String,
                  inputCols: Array[String],
                  outPut: String, outputOptionions: Map[String, Any], outputCols: Array[Map[String, String]], sql: String): Unit = {
    implicit val dispatch_task_id = dispatchOption.getOrElse("job_id", "001").toString
    val etl_date = JsonUtil.jsonToMap(dispatchOption.getOrElse("params", "").toString).getOrElse("ETL_DATE", "").toString;
    val owner = dispatchOption.getOrElse("owner", "001").toString
    val job_context = dispatchOption.getOrElse("job_context", "001").toString
    MDC.put("job_id", dispatch_task_id)
    try {
      logger.info("[数据采集]:数据采集开始")
      logger.info("[数据采集]:数据采集日期:" + etl_date)
      val df = inPutHandler(spark, task_logs_id, dispatchOption, etlTaskInfo, inPut, inputOptions, inputCondition, inputCols, outPut, outputOptionions, outputCols, sql)

      if (!inPut.toString.toLowerCase.equals("kafka") && !inPut.toString.toLowerCase.equals("flume")) {
        outPutHandler(spark, df, outPut, outputOptionions, outputCols, sql)
      } else {
        logger.info("[数据采集]:数据采集检测是实时采集,输出数据源为jdbc")
      }

      MariadbCommon.updateTaskStatus(task_logs_id, dispatch_task_id, "finish", etl_date, "100")
      if (outPut.trim.toLowerCase.equals("外部下载")) {
        //获取路径信息
        val root_path = outputOptionions.getOrElse("root_path", "")
        val paths = outputOptionions.getOrElse("paths", "")
        MariadbCommon.insertZdhDownloadInfo(root_path + "/" + paths + ".csv", Timestamp.valueOf(etl_date), owner, job_context)
      }
      logger.info("[数据采集]:数据采集完成")

    } catch {
      case ex: Exception => {
        ex.printStackTrace()
        logger.error("[数据采集]:[ERROR]:" + ex.getMessage, ex.getCause)
        MariadbCommon.updateTaskStatus(task_logs_id, dispatch_task_id, "error", etl_date, "")

      }
    } finally {
      MDC.remove("job_id")
    }

  }

  def DataHandlerMore(spark: SparkSession, task_logs_id: String, dispatchOption: Map[String, Any], dsi_EtlInfo: List[Map[String, Map[String, Any]]],
                      etlMoreTaskInfo: Map[String, Any], outPut: String, outputOptions: Map[String, Any], outputCols: Array[Map[String, String]], sql: String): Unit = {

    implicit val dispatch_task_id = dispatchOption.getOrElse("job_id", "001").toString
    val etl_date = JsonUtil.jsonToMap(dispatchOption.getOrElse("params", "").toString).getOrElse("ETL_DATE", "").toString;
    val owner = dispatchOption.getOrElse("owner", "001").toString
    val job_context = dispatchOption.getOrElse("job_context", "001").toString
    val drop_tmp_tables=etlMoreTaskInfo.getOrElse("drop_tmp_tables","").toString.trim match {
      case ""=>Array.empty[String]
      case a=>a.split(",")
    }
    MDC.put("job_id", dispatch_task_id)
    val tables = new util.ArrayList[String]();
    try {
      logger.info("[数据采集]:[多源]:数据采集开始")
      logger.info("[数据采集]:[多源]:数据采集日期:" + etl_date)
      val exe_sql = etlMoreTaskInfo.getOrElse("etl_sql", "").toString.replaceAll("\\$zdh_etl_date", "'" + etl_date + "'")
      if (exe_sql.trim.equals("")) {
        //logger.error("多源任务对应的单源任务说明必须包含# 格式 'etl任务说明#临时表名'")
        throw new Exception("多源任务处理逻辑必须不为空")
      }


      //多源处理
      dsi_EtlInfo.foreach(f => {

        //调用读取数据源
        //输入数据源信息
        val dsi_Input = f.getOrElse("dsi_Input", Map.empty[String, Any]).asInstanceOf[Map[String, Any]]
        //输入数据源类型
        val inPut = dsi_Input.getOrElse("data_source_type", "").toString
        val etlTaskInfo = f.getOrElse("etlTaskInfo", Map.empty[String, Any])
        //参数
        val inputOptions: Map[String, Any] = etlTaskInfo.getOrElse("data_sources_params_input", "").toString.trim match {
          case "" => Map.empty[String, Any]
          case a => JsonUtil.jsonToMap(a)
        }
        //过滤条件
        val filter = etlTaskInfo.getOrElse("data_sources_filter_input", "").toString
        //输入字段
        val inputCols: Array[String] = etlTaskInfo.getOrElse("data_sources_file_columns", "").toString.split(",")
        //输出字段
        val list_map = etlTaskInfo.getOrElse("column_data_list", null).asInstanceOf[List[Map[String, String]]]
        val outPutCols_tmp = list_map.toArray

        //生成table
        //获取表名
        if (!etlTaskInfo.getOrElse("etl_context", "").toString.contains("#")) {
          logger.error("多源任务对应的单源任务说明必须包含# 格式 'etl任务说明#临时表名'")
          throw new Exception("多源任务对应的单源任务说明必须包含# 格式 'etl任务说明#临时表名'")
        }

        val ds = inPutHandler(spark, task_logs_id, dispatchOption, etlTaskInfo, inPut, dsi_Input ++ inputOptions, filter, inputCols, null, null, outPutCols_tmp, null)

        val tableName = etlTaskInfo.getOrElse("etl_context", "").toString.split("#")(1)
        ds.createTempView(tableName)
        tables.add(tableName)
      })

      //执行sql
      val exe_sql_ary=exe_sql.split(";\r\n")
      var result:DataFrame=null
      exe_sql_ary.foreach(sql=>{
        if (!sql.trim.equals(""))
         result = spark.sql(sql)
      })

      //写入数据源
      outPutHandler(spark, result, outPut, outputOptions, null, sql)
      MariadbCommon.updateTaskStatus(task_logs_id, dispatch_task_id, "finish", etl_date, "100")
      if (outPut.trim.toLowerCase.equals("外部下载")) {
        //获取路径信息
        val root_path = outputOptions.getOrElse("root_path", "")
        val paths = outputOptions.getOrElse("paths", "")
        MariadbCommon.insertZdhDownloadInfo(root_path + "/" + paths + ".csv", Timestamp.valueOf(etl_date), owner, job_context)
      }

      logger.info("[数据采集]:[多源]:数据采集完成")
    } catch {
      case ex: Exception => {
        ex.printStackTrace()
        val line=System.getProperty("line.separator")
        val log=ex.getMessage.split(line).mkString(",")
        logger.info("[数据采集]:[多源]:[ERROR]:" +log.trim)
        MariadbCommon.updateTaskStatus(task_logs_id, dispatch_task_id, "error", etl_date, "")
      }
    } finally {
      MDC.remove("job_id")
      tables.toArray().foreach(table => {
        if (spark.catalog.tableExists(table.toString)) {
          logger.info("[数据采集]:[多源]:任务完成清空临时表:" + table.toString)
          drop_tmp_tables.foreach(table=>{
            spark.sql("drop view if EXISTS "+table).show()
          })
          spark.catalog.dropTempView(table.toString)
        }
      })

    }


  }

  def DataHandlerSql(spark: SparkSession, task_logs_id: String, dispatchOption: Map[String, Any], sqlTaskInfo: Map[String, Any], inPut: String, inputOptions: Map[String, Any],
                     outPut: String, outputOptionions: Map[String, Any], outputCols: Array[Map[String, String]], sql: String): Unit ={

    implicit val dispatch_task_id = dispatchOption.getOrElse("job_id", "001").toString
    val etl_date = JsonUtil.jsonToMap(dispatchOption.getOrElse("params", "").toString).getOrElse("ETL_DATE", "").toString;
    val owner = dispatchOption.getOrElse("owner", "001").toString
    val job_context = dispatchOption.getOrElse("job_context", "001").toString
    MDC.put("job_id", dispatch_task_id)
    try {
      logger.info("[数据采集]:[SQL]:数据采集开始")
      logger.info("[数据采集]:[SQL]:数据采集日期:" + etl_date)
      val etl_sql=sqlTaskInfo.getOrElse("etl_sql","").toString

      logger.info("[数据采集]:[SQL]:"+etl_sql)
      if (etl_sql.trim.equals("")) {
        //logger.error("多源任务对应的单源任务说明必须包含# 格式 'etl任务说明#临时表名'")
        throw new Exception("SQL任务处理逻辑必须不为空")
      }

      logger.info("[数据采集]:[SQL]:"+etl_sql)
      val df=DataWareHouseSources.getDS(spark,dispatchOption,inPut,inputOptions.asInstanceOf[Map[String,String]],
        null,null,null,outPut,outputOptionions.asInstanceOf[Map[String,String]],outputCols,etl_sql)

      outPutHandler(spark,df,outPut,outputOptionions,outputCols,sql)

      MariadbCommon.updateTaskStatus(task_logs_id, dispatch_task_id, "finish", etl_date, "100")
      if (outPut.trim.toLowerCase.equals("外部下载")) {
        //获取路径信息
        val root_path = outputOptionions.getOrElse("root_path", "")
        val paths = outputOptionions.getOrElse("paths", "")
        MariadbCommon.insertZdhDownloadInfo(root_path + "/" + paths + ".csv", Timestamp.valueOf(etl_date), owner, job_context)
      }
      logger.info("[数据采集]:[SQL]:数据采集完成")

    } catch {
      case ex: Exception => {
        ex.printStackTrace()
        logger.error("[数据采集]:[SQL]:[ERROR]:" + ex.getMessage, ex.getCause)
        MariadbCommon.updateTaskStatus(task_logs_id, dispatch_task_id, "error", etl_date, "")

      }
    } finally {
      MDC.remove("job_id")
    }





  }

  /**
    * 读取数据源handler
    *
    * @param spark
    * @param dispatchOption
    * @param inPut
    * @param inputOptions
    * @param inputCondition
    * @param inputCols
    * @param outPut
    * @param outputOptionions
    * @param outputCols
    * @param sql
    * @param dispatch_task_id
    */
  def inPutHandler(spark: SparkSession, task_logs_id: String, dispatchOption: Map[String, Any], etlTaskInfo: Map[String, Any], inPut: String, inputOptions: Map[String, Any], inputCondition: String,
                   inputCols: Array[String],
                   outPut: String, outputOptionions: Map[String, Any], outputCols: Array[Map[String, String]], sql: String)(implicit dispatch_task_id: String): DataFrame = {
    //调用对应的数据源
    //调用对应的中间处理层
    logger.info("[数据采集]:[输入]:开始匹配输入数据源")
    val etl_date = JsonUtil.jsonToMap(dispatchOption.getOrElse("params", "").toString).getOrElse("ETL_DATE", "").toString;
    val etl_task_id = etlTaskInfo.getOrElse("id", "001").toString
    val owner = dispatchOption.getOrElse("owner", "001").toString
    val error_rate = etlTaskInfo.getOrElse("error_rate", "0.01").toString match {
      case ""=>"0.01"
      case er=>er
    }
    val enable_quality = etlTaskInfo.getOrElse("enable_quality", "off").toString
    val duplicate_columns=etlTaskInfo.getOrElse("duplicate_columns", "").toString.trim match {
      case ""=>Array.empty[String]
      case a=>a.split(",")
    }
    val zdhDataSources: ZdhDataSources = inPut.toString.toLowerCase match {
      case "jdbc" => JdbcDataSources
      case "hdfs" => HdfsDataSources
      case "hive" => HiveDataSources
      //hbase 数据源不能直接使用expr 表达式
      case "hbase" => HbaseDataSources
      case "es" => ESDataSources
      case "mongodb" => MongoDBDataSources
      case "kafka" => KafKaDataSources
      case "http" => HttpDataSources
      case "redis" => RedisDataSources
      case "cassandra" => CassandraDataSources
      case "sftp" => SFtpDataSources
      case "kudu" => KuduDataSources
      case "外部上传" => LocalDataSources
      case "flume" => FlumeDataSources
      case "外部下载" => throw new Exception("[数据采集]:[输入]:[外部下载]只能作为输出数据源:")
      case _ => throw new Exception("数据源类型无法匹配")
    }
    var outputCols_expr: Array[Column] = null
    if (!inPut.toLowerCase.equals("hbase")) {
      outputCols_expr = outputCols.map(f => {
        if (f.getOrElse("column_alias", "").toLowerCase.equals("row_key")) {
          expr(f.getOrElse("column_expr", "")).cast("string").as(f.getOrElse("column_alias", ""))
        } else {
          if (!f.getOrElse("column_type", "").trim.equals("")) {
            //类型转换
            if (f.getOrElse("column_expr", "").contains("$zdh_etl_date")) {
              expr(f.getOrElse("column_expr", "").replaceAll("\\$zdh_etl_date", "'" + etl_date + "'")).cast(f.getOrElse("column_type", "string")).as(f.getOrElse("column_alias", ""))
            } else {
              expr(f.getOrElse("column_expr", "")).cast(f.getOrElse("column_type", "string")).as(f.getOrElse("column_alias", ""))
            }
          } else {
            //默认类型
            if (f.getOrElse("column_expr", "").contains("$zdh_etl_date")) {
              expr(f.getOrElse("column_expr", "").replaceAll("\\$zdh_etl_date", "'" + etl_date + "'")).as(f.getOrElse("column_alias", ""))
            } else {
              expr(f.getOrElse("column_expr", "")).as(f.getOrElse("column_alias", ""))
            }
          }

        }
      })
    }
    if(outputCols_expr==null){
      outputCols_expr=Array.empty[Column]
    }

    val primary_columns = etlTaskInfo.getOrElse("primary_columns", "").toString
    val column_size = etlTaskInfo.getOrElse("column_size", "").toString match {
      case ""=>0
      case cs=>cs.toInt
    }
    val rows_range = etlTaskInfo.getOrElse("rows_range", "").toString

    var column_is_null: Seq[Column] = Seq.empty[Column]
    var column_length: Seq[Column] = Seq.empty[Column]
    var column_regex: Seq[Column] = Seq.empty[Column]

    val zdh_regex=udf((c1:String,re:String)=>{
      val a=re.r()
      c1.matches(a.toString())
    })

    outputCols.foreach(f => {
      if (f.getOrElse("column_is_null", "true").trim.equals("false")) {
        if (f.getOrElse("column_name", "").trim.equals("")) {
          throw new Exception("字段是否为空检测,需要的原始字段列名为空")
        }
        val c1 = col(f.getOrElse("column_name", "").trim).isNull
        column_is_null = column_is_null.:+(c1)

      }
      if (!f.getOrElse("column_regex", "").trim.equals("")) {
        if (f.getOrElse("column_name", "").trim.equals("")) {
          throw new Exception("字段是否为空检测,需要的原始字段列名为空")
        }
        val c1=zdh_regex(col(f.getOrElse("column_name", "").trim),lit(f.getOrElse("column_regex", "").trim))
        column_regex = column_regex.:+(c1)
      }

      if (!f.getOrElse("column_size", "").trim.equals("")) {

      }

      if (!f.getOrElse("column_length", "").trim.equals("")) {
        if (f.getOrElse("column_name", "").trim.equals("")) {
          throw new Exception("字段长度质量检测,需要的原始字段列名为空")
        }
        val c1 = length(col(f.getOrElse("column_name", "").trim)) =!= f.getOrElse("column_length", "").trim
        column_length = column_length.:+(c1)
      }

    })

    MariadbCommon.updateTaskStatus(task_logs_id, dispatch_task_id, "etl", etl_date, "25")
    val df = zdhDataSources.getDS(spark, dispatchOption, inPut, inputOptions.asInstanceOf[Map[String, String]],
      inputCondition, inputCols, duplicate_columns,outPut, outputOptionions.asInstanceOf[Map[String, String]], outputCols, sql)
    MariadbCommon.updateTaskStatus(task_logs_id, dispatch_task_id, "etl", etl_date, "50")

    if (enable_quality.trim.equals("on")) {
      logger.info("任务开启了质量检测,开始进行质量检测")
      val report = zdhDataSources.dataQuality(spark, df, error_rate, primary_columns, column_size, rows_range, column_is_null, column_length,column_regex)

      MariadbCommon.insertQuality(task_logs_id, dispatch_task_id, etl_task_id, etl_date, report, owner)
      if (report.getOrElse("result", "").equals("不通过")) {
        throw new Exception("ETL 任务做质量检测时不通过,具体请查看质量检测报告")
      }
      logger.info("完成质量检测")
    }

    zdhDataSources.process(spark, df, outputCols_expr, etl_date)
  }


  /**
    * 输出数据源处理
    *
    * @param spark
    * @param df
    * @param outPut
    * @param outputOptionions
    * @param outputCols
    * @param sql
    * @param dispatch_task_id
    */
  def outPutHandler(spark: SparkSession, df: DataFrame,
                    outPut: String, outputOptionions: Map[String, Any], outputCols: Array[Map[String, String]], sql: String)(implicit dispatch_task_id: String): Unit = {
    try {

      logger.info("[数据采集]:[输出]:开始匹配输出数据源")
      //调用写入数据源
      val zdhDataSources: ZdhDataSources = outPut.toString.toLowerCase match {
        case "jdbc" => {
          logger.info("[数据采集]:[输出]:输出源为[JDBC]")
          JdbcDataSources
        }
        case "hive" => {
          logger.info("[数据采集]:[输出]:输出源为[HIVE]")
          HiveDataSources
        }
        case "hdfs" => {
          logger.info("[数据采集]:[输出]:输出源为[HDFS]")
          HdfsDataSources
        }
        case "hbase" => {
          logger.info("[数据采集]:[输出]:输出源为[HBASE]")
          HbaseDataSources
        }
        case "es" => {
          logger.info("[数据采集]:[输出]:输出源为[ES]")
          ESDataSources
        }
        case "mongodb" => {
          logger.info("[数据采集]:[输出]:输出源为[MONGODB]")
          MongoDBDataSources
        }
        case "kafka" => {
          logger.info("[数据采集]:[输出]:输出源为[KAFKA]")
          KafKaDataSources
        }
        case "redis" => {
          logger.info("[数据采集]:[输出]:输出源为[REDIS]")
          RedisDataSources
        }
        case "cassandra" => {
          logger.info("[数据采集]:[输出]:输出源为[CASSANDRA]")
          CassandraDataSources
        }
        case "sftp" => {
          logger.info("[数据采集]:[输出]:输出源为[SFTP]")
          SFtpDataSources
        }
        case "kudu" => {
          logger.info("[数据采集]:[输出]:输出源为[KUDU]")
          KuduDataSources
        }
        case "外部上传" => throw new Exception("[数据采集]:[输出]:[外部上传]只能作为输入数据源:")
        case "外部下载" => DownDataSources
        case x => throw new Exception("[数据采集]:[输出]:无法识别输出数据源:" + x)
      }

      zdhDataSources.writeDS(spark, df, outputOptionions.asInstanceOf[Map[String, String]], sql)

    } catch {
      case ex: Exception => {
        ex.printStackTrace()
        logger.error("[数据采集]:[输出]:[ERROR]:" + ex.getMessage)
        throw ex
      }
    }

  }


}