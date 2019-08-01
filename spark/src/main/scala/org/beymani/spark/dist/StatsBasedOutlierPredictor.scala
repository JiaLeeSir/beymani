/*
 * beymani-spark: Outlier and anamoly detection 
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.beymani.spark.dist

import org.chombo.spark.common.JobConfiguration
import org.apache.spark.SparkContext
import scala.collection.JavaConverters._
import org.chombo.util.BasicUtils
import org.chombo.spark.common.Record
import org.chombo.util.BaseAttribute
import com.typesafe.config.Config
import java.lang.Boolean
import org.beymani.predictor.ZscorePredictor
import org.beymani.predictor.ExtremeValuePredictor
import org.beymani.predictor.RobustZscorePredictor
import org.chombo.util.SeasonalAnalyzer
import org.chombo.spark.common.SeasonalUtility
import org.beymani.predictor.EstimatedProbabilityBasedPredictor
import org.beymani.predictor.EsimatedAttrtibuteProbabilityBasedPredictor
import org.beymani.predictor.InterPercentileDifferenceBasedPredictor
import org.apache.spark.Accumulator
import org.chombo.spark.common.GeneralUtility
import org.beymani.predictor.EstimatedMetaProbabilityBasedPredictor

object StatsBasedOutlierPredictor extends JobConfiguration with SeasonalUtility with GeneralUtility{
   private val predStrategyZscore = "zscore";
   private val predStrategyRobustZscore = "robustZscore";
   private val predStrategyEstProb = "estimatedProbablity";
   private val predStrategyEstAttrProb = "estimatedAttributeProbablity";
   private val predStrategyExtremeValueProb = "extremeValueProbablity";
   private val predStrategyInterPercentDiff = "interPercentileDifference";
   private val predStrategyEstMetaProb = "estimatedMetaProbablity";
   
   /**
   * @param args
   * @return
   */
   def main(args: Array[String]) {
	   val appName = "statsBasedOutlierPredictor"
	   val Array(inputPath: String, outputPath: String, configFile: String) = getCommandLineArgs(args, 3)
	   val config = createConfig(configFile)
	   val sparkConf = createSparkConf(appName, config, false)
	   val sparkCntxt = new SparkContext(sparkConf)
	   val appConfig = config.getConfig(appName)
	   
	   //configuration params
	   val fieldDelimIn = appConfig.getString("field.delim.in")
	   val fieldDelimOut = appConfig.getString("field.delim.out")
	   
	   val predictorStrategy = getMandatoryStringParam(appConfig, "predictor.strategy", "missing prediction strategy")
	   val predictorStrategies = Array[String]("zscore", "robustZscore", "estimatedProbablity", 
	       "estimatedAttributeProbablity", "extremeValueProbablity")
	   assertStringMember(predictorStrategy, predictorStrategies, "invalid prediction strategy " + predictorStrategy)

	   val appAlgoConfig = appConfig.getConfig(predictorStrategy)
	   val algoConfig = getConfig(predictorStrategy, appConfig, appAlgoConfig)
	   val scoreThreshold:java.lang.Double = getMandatoryDoubleParam(appConfig, "score.threshold", "missing score threshold")
	   val precision = getIntParamOrElse(appConfig, "output.precision", 3)
	   val keyFields = getOptionalIntListParam(appConfig, "id.fieldOrdinals")
	   val keyFieldOrdinals = keyFields match {
	     case Some(fields:java.util.List[Integer]) => Some(fields.asScala.toArray)
	     case None => None  
	   }
	   val outputOutliers = getBooleanParamOrElse(appConfig, "output.outliers", false)
	   val remOutliers = getBooleanParamOrElse(appConfig, "rem.outliers", false)
	   val cleanDataDirPath = getConditionalMandatoryStringParam(remOutliers, appConfig, "clean.dataDirPath", 
	       "missing clean data file output directory")
	   val seasonalTypeFldOrd = getOptionalIntParam(appConfig, "seasonal.typeFldOrd")
	   
	   //seasonal data
	   val seasonalAnalysis = getBooleanParamOrElse(appConfig, "seasonal.analysis", false)
	   val partBySeasonCycle = getBooleanParamOrElse(appConfig, "part.bySeasonCycle", true)
	   val analyzerMap = scala.collection.mutable.Map[String, (SeasonalAnalyzer, Int)]()
	   val seasonalAnalyzers = if (seasonalAnalysis) {
		   	val seasonalCycleTypes = getMandatoryStringListParam(appConfig, "seasonal.cycleType", 
	        "missing seasonal cycle type").asScala.toArray
	        val timeZoneShiftHours = getIntParamOrElse(appConfig, "time.zoneShiftHours", 0)
	        val timeStampFieldOrdinal = getMandatoryIntParam(appConfig, "time.fieldOrdinal", 
	        "missing time stamp field ordinal")
	        val timeStampInMili = getBooleanParamOrElse(appConfig, "time.inMili", true)
	        
	        val analyzers = seasonalCycleTypes.map(sType => {
	        	val seasonalAnalyzer = createSeasonalAnalyzer(this, appConfig, sType, timeZoneShiftHours, timeStampInMili)
	        	analyzerMap += (sType -> (seasonalAnalyzer, timeStampFieldOrdinal))
	        	seasonalAnalyzer
	        })
	        Some((analyzers, timeStampFieldOrdinal))
	   } else {
		   	None
	   }
	   
	   //soft threshold below actual
	   val thresholdNorm = getOptionalDoubleParam(appConfig, "score.thresholdNorm")
	     
	   //outlier polarity high, low or both
	   val applyPolarity = getBooleanParamOrElse(appConfig, "apply.polarity", false)
	   val outlierPolarity = getStringParamOrElse(appConfig, "outlier.polarity", "both")
	   val statValues = 
	   if (applyPolarity) {
	     if (getMandatoryIntListParam(appConfig, "attr.ordinals").size() != 1) {
	       throw new IllegalStateException("outlier polarity can be applied only for one quant field, found multiple")
	     }
	     
	     val statsPath = getMandatoryStringParam(appConfig, "stats.file.path", "missing stat file path")
	     var keyLen = 0
	     keyFieldOrdinals match {
	     	case Some(fields : Array[Integer]) => keyLen +=  fields.length
	     	case None =>
	     }
	     keyLen += (if (seasonalAnalysis) 2 else 0)
	     keyLen += 1
	     val meanFldOrd = keyLen + getIntParamOrElse(appConfig, "mean.fldOrd",4)
	     val stdDevFieldOrd = keyLen + getIntParamOrElse(appConfig, "stdDev.fldOrd",6)
	     //println("keyLen " + keyLen + " meanFldOrd " + meanFldOrd)
	     val meanMap = BasicUtils.getKeyedValues(statsPath, keyLen, meanFldOrd)
	     val stdDevMap = BasicUtils.getKeyedValues(statsPath, keyLen, stdDevFieldOrd)
	     (meanMap, stdDevMap)
	   } else {
	     null
	   }
	   val stdDevMult = getDoubleParamOrElse(appConfig, "stdDev.mult", 0)
	   
	   //val brMeanValues = sparkCntxt.broadcast(meanValues)
	   val quantFldOrd = getMandatoryIntListParam(appConfig, "attr.ordinals").get(0).toInt
	   
	   val debugOn = appConfig.getBoolean("debug.on")
	   val saveOutput = appConfig.getBoolean("save.output")

	   val predictor = predictorStrategy match {
       	  case `predStrategyZscore` => new ZscorePredictor(algoConfig, "id.fieldOrdinals", "attr.ordinals", 
       	    "field.delim.in", "attr.weights", "stats.filePath", "seasonal.analysis", "hdfs.file", "score.threshold",
       	    "exp.const", "ignore.missingModel")
         
       	  case `predStrategyExtremeValueProb` => new ExtremeValuePredictor(algoConfig, "id.fieldOrdinals", "attr.ordinals", 
       	    "field.delim.in", "attr.weights", "stats.filePath", "seasonal.analysis", "hdfs.file", "score.threshold",
       	    "exp.const", "ignore.missingModel")

       	  case `predStrategyRobustZscore` => new RobustZscorePredictor(algoConfig, "id.fieldOrdinals", "attr.ordinals", 
       	     "stats.medFilePath", "stats.madFilePath", "field.delim.in", "attr.weights","seasonal.analysis",
       	     "hdfs.file", "exp.const","score.threshold", "ignore.missingModel");
     
       	  case `predStrategyEstProb` => new EstimatedProbabilityBasedPredictor(algoConfig, "id.fieldOrdinals", 
       	     "distr.file.path", "hdfs.file", "schema.file.path", "seasonal.analysis", "field.delim.in", 
       	      "score.threshold")
       	
       	  case `predStrategyEstAttrProb` => new EsimatedAttrtibuteProbabilityBasedPredictor(algoConfig, 
       	    "id.fieldOrdinals", "attr.ordinals","distr.filePath", "hdfs.file", "schema.filePath", 
       	    "attr.weights", "seasonal.analysis", "field.delim.in", "score.threshold", "ignore.missingModel", "score.strategy")

       	  case `predStrategyInterPercentDiff` => new InterPercentileDifferenceBasedPredictor(algoConfig, 
       	    "id.fieldOrdinals", "attr.ordinals","distr.filePath", "hdfs.file", "schema.filePath", 
       	    "attr.weights", "seasonal.analysis", "field.delim.in", "score.threshold", "ignore.missingModel")
	   
       	  case `predStrategyEstMetaProb` => new EstimatedMetaProbabilityBasedPredictor(algoConfig, 
       	    "id.fieldOrdinals", "attr.ordinals","distr.filePath", "hdfs.file", "schema.filePath", 
       	    "attr.weights", "seasonal.analysis", "field.delim.in", "score.threshold", "ignore.missingModel", "score.strategy")
	   }
	   
	   val ignoreMissingStat = getBooleanParamOrElse(appConfig, "ignore.missingStat", false)
	   predictor.withIgnoreMissingStat(ignoreMissingStat)
	   
	   //broadcast validator
	   val brPredictor = sparkCntxt.broadcast(predictor)
	 
	   //counters
	   val lowIgnoredCounter = sparkCntxt.accumulator(0)
	   val highIgnoredCounter = sparkCntxt.accumulator(0)
	   val noIgnoredCounter = sparkCntxt.accumulator(0)
	   val invalidScoreCounter = sparkCntxt.accumulator(0)
	   
	   //input
	   val data = sparkCntxt.textFile(inputPath)
	   if (remOutliers)
	     data.cache
	   
	   //predict for each field in each line whether it's an outlier
	   var keyLen = 0
	   keyFieldOrdinals match {
	      case Some(fields : Array[Integer]) => keyLen +=  fields.length
	      case None =>
	   }
	   keyLen += (if (seasonalAnalysis) 2 else 0)

	   var taggedData = data.map(line => {
		   val items = BasicUtils.getTrimmedFields(line, fieldDelimIn)
		   val key = Record(keyLen)
		   //partioning fields
		   keyFieldOrdinals match {
	           case Some(fields : Array[Integer]) => {
	             for (kf <- fields) {
	               key.addString(items(kf))
	             }
	           }
	           case None =>
	       }
		   
		   seasonalTypeFldOrd match {
		     //seasonal type field in data
		     case Some(seasonalOrd:Int) => {
		       val seasonalType = items(seasonalOrd)
		       val analyzer = analyzerMap.get(seasonalType)
		       analyzer match {
		         case Some(an:(SeasonalAnalyzer, Int)) => {
		            val analyzer = an._1
		        	val tsFldOrd = an._2
		            val timeStamp = items(tsFldOrd).toLong
		            val cIndex = SeasonalAnalyzer.getCycleIndex(analyzer, timeStamp)
		            key.addString(cIndex.getLeft())
		            key.addInt(cIndex.getRight())
		         }
		         //unexpected
		         case None => throw new IllegalStateException("missing seasonal analyzer")
		       }
		     }
		     
		     //seasonal type in configuration
		     case None => {
			   seasonalAnalyzers match {
			     case Some(seAnalyzers : (Array[SeasonalAnalyzer], Int)) => {
			    	 val tsFldOrd = seAnalyzers._2
			         val timeStamp = items(tsFldOrd).toLong
			         val analyzers = seAnalyzers._1
			         val cIndex = SeasonalAnalyzer.getCycleIndex(analyzers, timeStamp)
			         key.addString(cIndex.getLeft())
			         key.addInt(cIndex.getRight())
			       }
			     //not seasonal
			     case None => 
			   }	
		     }
		   }
		     
		   if (seasonalAnalysis &&  key.getInt(key.size - 1) == -1) {
		       //seasonal but invalid cycle index
			   line + fieldDelimOut + BasicUtils.formatDouble(0.0, precision) + fieldDelimOut + "A"
		   } else {
			   //other cases
			   val keyStr = key.toString
			   val predictor = brPredictor.value
			   val score:java.lang.Double = predictor.execute(items, keyStr)
			   var marker = if (score > scoreThreshold) "O"  else "N"
			   if (!predictor.isValid(keyStr))  {
			     //invalid prediction because of missing model
			     marker = "I"
			     invalidScoreCounter += 1
			   }
			   
			   marker = 
			   if (applyPolarity && !marker.equals("I")) {
				   val mValues = statValues._1
				   val sdValues = statValues._2
				   val keyWithFldOrd = keyStr + fieldDelimIn + quantFldOrd
				   applyPolarityToOutlier(items, quantFldOrd, marker, outlierPolarity, keyWithFldOrd, mValues, 
				       sdValues, stdDevMult, lowIgnoredCounter, highIgnoredCounter, noIgnoredCounter)
			   } else {
			     marker
			   }
			  line + fieldDelimOut + BasicUtils.formatDouble(score, precision) + fieldDelimOut + marker
		   }
	   })
	 
	   if (outputOutliers || remOutliers) {
	     taggedData = taggedData.filter(line => {
		   val items = line.split(fieldDelimIn, -1)
		   val marker = items(items.length - 1)
		   marker.equals("O")
	       })
	     if (remOutliers) {
	       //additional output for input with outliers subtracted
	       taggedData = taggedData.map(line => {
		     val items = line.split(fieldDelimIn, -1)
	         val ar = items.slice(0, items.length - 2)
	         ar.mkString(fieldDelimOut)
	       })
	     
	       //remove outliers records
	       val cleanData =  data.subtract(taggedData)
	       cleanData.saveAsTextFile(cleanDataDirPath) 
	     }
	   } else {
	     //all or only records above a threshold
	     taggedData =  thresholdNorm match {
	       case Some(threshold:Double) => {
	         taggedData.filter(line => {
	           val items = line.split(fieldDelimIn, -1)
	           val score = items(items.length - 2).toDouble
	           score > threshold
	         })
	       }
	       case None => taggedData
	     }
	   }
	 

	 if (debugOn) {
         val records = taggedData.collect
         records.slice(0, 100).foreach(r => println(r))
     }
	   
	 if(saveOutput) {	   
	     taggedData.saveAsTextFile(outputPath) 
	 }	 
	 
	 println("** counters **")
	 println("low value ignored counter " + lowIgnoredCounter.value)
	 println("high value ignored counter " + highIgnoredCounter.value)
	 println("invalid score counter " + invalidScoreCounter.value)
   }
   
      /**
   * @param args
   * @return
   */
   def getConfig(predictorStrategy : String, appConfig : Config,  appAlgoConfig : Config) : java.util.Map[String, Object] = {
	   val configParams = new java.util.HashMap[String, Object]()
	   val partIdOrds = getOptionalIntListParam(appConfig, "id.fieldOrdinals");
	   val idOrdinals = partIdOrds match {
	     case Some(idOrdinals: java.util.List[Integer]) => BasicUtils.fromListToIntArray(idOrdinals)
	     case None => null
	   }
	   configParams.put("id.fieldOrdinals", idOrdinals)
	   
	   val attrOrds = BasicUtils.fromListToIntArray(getMandatoryIntListParam(appConfig, "attr.ordinals"))
	   configParams.put("attr.ordinals", attrOrds)
	   
	   val fieldDelimIn = getStringParamOrElse(appConfig, "field.delim.in", ",")
	   configParams.put("field.delim.in", fieldDelimIn)

	   val scoreThreshold:java.lang.Double = getMandatoryDoubleParam(appConfig, "score.threshold", "missing score threshold")
	   configParams.put("score.threshold", scoreThreshold)
	   
	   val seasonalAnalysis:java.lang.Boolean = getBooleanParamOrElse(appConfig, "seasonal.analysis", false)
	   configParams.put("seasonal.analysis", seasonalAnalysis)
	   
	   val expConst :java.lang.Double = getDoubleParamOrElse(appConfig, "exp.const", 1.0)
	   configParams.put("exp.const", expConst)
	   
	   val isHdfsFile = getBooleanParamOrElse(appConfig, "hdfs.file", false)
	   configParams.put("hdfs.file", new java.lang.Boolean(isHdfsFile))
	   
	   val ignoreMissingModel = getBooleanParamOrElse(appConfig, "ignore.missingModel", true)
	   configParams.put("ignore.missingModel", new java.lang.Boolean(ignoreMissingModel))
	       
	   val attWeightList = getMandatoryDoubleListParam(appConfig, "attr.weights", "missing attribute weights")
	   val attrWeights = BasicUtils.fromListToDoubleArray(attWeightList)
	   configParams.put("attr.weights", attrWeights)

	   predictorStrategy match {
	     case `predStrategyZscore` => {
	       val statsFilePath = getMandatoryStringParam(appAlgoConfig, "stats.file.path", "missing stat file path")
	       configParams.put("stats.filePath", statsFilePath)
	     }
	     case `predStrategyRobustZscore` => {
	       val medStatsFilePath = getMandatoryStringParam(appAlgoConfig, "med.stats.file.path", "missing median stat file path")
	       configParams.put("stats.medFilePath", medStatsFilePath)
	       val madStatsFilePath = getMandatoryStringParam(appAlgoConfig, "mad.stats.file.path", "missing mad stat file path")
	       configParams.put("stats.madFilePath", madStatsFilePath)
	     }
	     case `predStrategyExtremeValueProb` => {
	       val medStatsFilePath = getMandatoryStringParam(appAlgoConfig, "stats.medFilePath", "missing med stat file path")
	       configParams.put("stats.medFilePath", medStatsFilePath)
	       val madStatsFilePath = getMandatoryStringParam(appAlgoConfig, "stats.madFilePath", "missing mad stat file path")
	       configParams.put("stats.madFilePath", madStatsFilePath)
	     }
	     case `predStrategyEstProb` => {
	       val distrFilePath = getMandatoryStringParam(appAlgoConfig, "distr.file.path", "missing distr file path")
	       configParams.put("distr.filePath", distrFilePath)
	       val schemaFilePath = getMandatoryStringParam(appAlgoConfig, "schema.file.path", "missing schema file path")
	       configParams.put("schema.filePath", schemaFilePath)
	     }
	     case `predStrategyEstAttrProb` => {
	       val distrFilePath = getMandatoryStringParam(appAlgoConfig, "distr.file.path", "missing distr file path")
	       configParams.put("distr.filePath", distrFilePath)
	       configParams.put("schema.filePath", null)
	       val olScoreStrategy = getStringParamOrElse(appAlgoConfig, "score.strategy", "inverse")
	       configParams.put("score.strategy", olScoreStrategy)
	     }
	     case `predStrategyInterPercentDiff` => {
	       val distrFilePath = getMandatoryStringParam(appAlgoConfig, "distr.file.path", "missing distr file path")
	       configParams.put("distr.filePath", distrFilePath)
	       configParams.put("schema.filePath", null)
	     }
	     case `predStrategyEstMetaProb` => {
	       val distrFilePath = getMandatoryStringParam(appAlgoConfig, "distr.file.path", "missing distr file path")
	       configParams.put("distr.filePath", distrFilePath)
	       configParams.put("schema.filePath", null)
	       val olScoreStrategy = getStringParamOrElse(appAlgoConfig, "score.strategy", "inverse")
	       configParams.put("score.strategy", olScoreStrategy)
	     }
	   }
	   
	   configParams
   }

   /**
   * @param config
   * @param paramName
   * @param defValue
   * @param errorMsg
   * @return
   */
   def applyPolarityToOutlier(items:Array[String], quantFldOrd:Int, label:String, outlierPolarity:String, key:String, 
       meanValues:java.util.Map[String,java.lang.Double], stdDevValues:java.util.Map[String,java.lang.Double], stdDevMult:Double,
       lowIgnoredCounter:Accumulator[Int], highIgnoredCounter:Accumulator[Int], noIgnoredCounter:Accumulator[Int]) : String = {
	   var newLabel = label
	   val value = items(quantFldOrd).toDouble
	   val mean = meanValues.get(key)
	   if (null != mean) {
		   val hiThreshold = meanValues.get(key) + stdDevMult * stdDevValues.get(key)
		   val loThreshold = meanValues.get(key) - stdDevMult * stdDevValues.get(key)
		   
		   if (label.equals("O")) {
		     if (outlierPolarity.equals("high")) {
		       if (value < hiThreshold) {
		         newLabel = "N"
		         lowIgnoredCounter += 1
		       }
		     } else if (outlierPolarity.equals("low")) {
		       if (value > loThreshold) {
		         newLabel = "N"
		         highIgnoredCounter += 1
		       }
		     } else {
		       if (value > loThreshold && value < hiThreshold) {
		         newLabel = "N"
		         noIgnoredCounter += 1
		       }
		       
		     }
	       } 
	       newLabel
	   } else {
	     println("stats not found for key " + key + " record marker "  + newLabel)
	     newLabel
	   }
   }
   
}