package com.rackspace.feeds.ballista

import java.io.{ByteArrayOutputStream, PrintWriter, Writer}

import com.rackspace.feeds.ballista.config.AppConfig.export.from.dbs._
import com.rackspace.feeds.ballista.config.AppConfig.export.to.hdfs.scp._
import com.rackspace.feeds.ballista.config.CommandOptions
import com.rackspace.feeds.ballista.constants.DBProps
import com.rackspace.feeds.ballista.service.{LocalFSClient, DefaultExportSvc}
import com.rackspace.feeds.ballista.util.{SCPSessionInfo, SCPUtil}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory

import scala.collection.mutable.{HashMap, MultiMap, Set}

class CommandProcessor {

  val logger = LoggerFactory.getLogger(getClass)
  val fsClient = new LocalFSClient

  val scpUtil = new SCPUtil
  val sessionInfo = new SCPSessionInfo(user, password, host, port, privateKeyFilePath, privateKeyPassPhrase)
  
  def doProcess(commandOptions: CommandOptions): Unit = {
    logger.info(s"Process is being run with these options $commandOptions")

    val queryParams: Map[String, Any] = Map("runDate" -> commandOptions.runDate)

    //creates a map of outputLocation and Set[dbNames] storing data in that output location
    val mm = new HashMap[String, Set[String]] with MultiMap[String, String]

    val resultMap = commandOptions.dbNames.map( dbName => {
      val outputFileLocation = dbConfigMap(dbName)(DBProps.outputFileLocation).replaceFirst("/$", "")
      mm.addBinding(outputFileLocation, dbName)

      dbName -> new DefaultExportSvc(dbName).export(queryParams)
    }).toMap

    if (commandOptions.dbNames.size == resultMap.keySet.size) {
      createSuccessFile(resultMap, mm, commandOptions.runDate)
    } else {
      val dbNamesMissingResults = commandOptions.dbNames.filterNot(resultMap.keySet).mkString(",")
      logger.error("!!!!!! Results missing for some dbNames[$dbNamesMissingResults] !!!!!")
    }

  }


  /**
   * This method creates a _SUCCESS file for each unique output file location. If multiple databases
   * have the same output file location, the _SUCCESS file will indicate the success of each of these
   * databases and will have information of each of them.
   * 
   * Ths _SUCCESS file will contain data in the below format.
   * 
   * dbName1=<number of records exported> 
   * dbName2=<number of records exported>
   * 
   * @param resultMap contains dbName -> <number of records exported> mapping
   * @param outputLocationMap contains an internal mapping of outputFileLocation -> Set[dbName]
   * @param runDate
   */
  def createSuccessFile(resultMap: Map[String, Long],
                        outputLocationMap: HashMap[String, Set[String]] with MultiMap[String, String],
                        runDate: DateTime): Unit = {
    
    val dateTimeStr = DateTimeFormat.forPattern("yyyy-MM-dd").print(runDate)
    
    outputLocationMap.foreach {
      case (outputFileLocation, dbNameSet) => {
        logger.info(s"Writing success file in $outputFileLocation for databases[${dbNameSet.mkString(",")}]")
        
        val successFileName = s"$outputFileLocation/_SUCCESS"

        val localFilePath: String = java.io.File.createTempFile("temp", "_success").getAbsolutePath
        val writer: Writer = getWriter(localFilePath)
        
        try {
          
          dbNameSet.foreach(dbName => {
            val numberOfRecordsWritten = resultMap.getOrElse(dbName, Long.MinValue)
            writer.write(s"$dbName=$numberOfRecordsWritten\n")
          })

        } finally {
          writer.close()
        }
        scpFile(localFilePath, successFileName, dateTimeStr)

        logger.info(s"Completed writing success file $successFileName")
      }
    }
  }
  
  def getWriter(filePath: String) = {
    new PrintWriter(fsClient.getOutputStream(filePath))
  }

  /**
   * SCP file present in $localFilePath to $remoteFilePath. If the remote file needs be placed in a 
   * new remote directory at the end of the current path in $remoteFilePath, $newSubDir value will
   * be used to create that new directory.
   *  
   * @param localFilePath
   * @param remoteFilePath
   * @param newSubDir
   */
  def scpFile(localFilePath: String, remoteFilePath: String, newSubDir: String) = {
    val remoteOutputFileLocation = remoteFilePath.substring(0, remoteFilePath.lastIndexOf("/"))
    val remoteOutputFileName = remoteFilePath.substring(remoteFilePath.lastIndexOf("/") + 1)
    
    scpUtil.scp(sessionInfo, localFilePath, remoteOutputFileName, remoteOutputFileLocation, newSubDir)
  }
  
}
