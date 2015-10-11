package com.run

import org.apache.spark.SparkContext
import org.apache.spark.SparkConf

import com.redislabs.provider.redis._


import java.net.InetAddress
import java.util

import org.apache.spark.rdd.RDD
import org.apache.spark._
import redis.clients.jedis._
import redis.clients.util.JedisClusterCRC16

import scala.collection.JavaConversions._
import com.redislabs.provider.util.ImportTimeSeriesData._
import com.redislabs.provider.util.GenerateWorkdayTestData._
import com.redislabs.provider.redis.partitioner._
import com.redislabs.provider.RedisConfig
import com.redislabs.provider.redis._

import com.cloudera.sparkts._
import com.cloudera.sparkts.DateTimeIndex._

import com.github.nscala_time.time.Imports._

import breeze.linalg._
import breeze.numerics._

import com.cloudera.finance.YahooParser
import com.cloudera.sparkts.DateTimeIndex._
import com.cloudera.sparkts.TimeSeries
import com.cloudera.sparkts.UnivariateTimeSeries._
import com.cloudera.sparkts.TimeSeriesRDD._
import com.cloudera.sparkts.TimeSeriesStatisticalTests._

import com.github.nscala_time.time.Imports._

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD

import org.joda.time.DateTimeZone.UTC

import java.io._

object Main {
  
  def averageTime(rdd1: RDD[(String, Vector[Double])], rdd2: RDD[(String, Vector[Double])], cnt: Int, writer: java.io.PrintWriter) {
    val startTimerdd1 = System.currentTimeMillis
    (0 until cnt).foreach(x => rdd1.collect())
    val endTimerdd1 = System.currentTimeMillis
    val period1 = (endTimerdd1 - startTimerdd1) / 1000.0 / cnt
    writer.write(f"TimeSeriesRDD: ${period1}%.2f s\n")
    
    val startTimerdd2 = System.currentTimeMillis
    (0 until cnt).foreach(x => rdd2.collect())
    val endTimerdd2 = System.currentTimeMillis
    val period2 = (endTimerdd2 - startTimerdd2) / 1000.0 / cnt
    writer.write(f"RedisTimeSeriesRDD: ${period2}%.2f s\n")
    
    val improve = (period1 - period2) * 100.0 / period1
    writer.write(f"Improved by: ${improve}%.2f %%\n\n\n")
  }
  
  def rddEquals(rdd: RDD[(String, Vector[Double])], Redisrdd: RDD[(String, Vector[Double])]): Boolean = {
    val rdd1collect = rdd.collect()
    val rdd2collect = Redisrdd.collect()
    if (rdd1collect.size != rdd2collect.size) {
      return false
    }
    else {
      for (i <- 0 to (rdd1collect.size - 1).toInt) {
        val arr1 = rdd1collect(i)._2
        val arr2 = rdd2collect.filter(x => {x._1 == rdd1collect(i)._1})(0)._2
        if (arr1.size != arr2.size) {
          return false
        }
        else {
          for (j <- 0 to (arr1.size - 1).toInt) {
            if (abs(arr1(j) - arr2(j)) > 0.01) {
              return false
            }
          }
        }
      }
    }
    return true
  }
  
  def TEST(sc: SparkContext, writer: PrintWriter, cnt: Int, msg: String, dir: String, prefix: String, redisNode: (String, Int)) {
    val jedis = new Jedis(redisNode._1, redisNode._2)
    jedis.flushAll()
    Thread sleep 8000
    jedis.close
    ImportToRedisServer(dir, prefix, sc, redisNode)
    
    writer.write("****** "+ msg + " ******\n")
    val seriesByFile: RDD[TimeSeries] = YahooParser.yahooFiles(dir, sc)

    val start = seriesByFile.map(_.index.first).takeOrdered(1).head
    val end = seriesByFile.map(_.index.last).top(1).head
    val dtIndex = uniform(start, end, 1.businessDays)
    
    val Rdd = timeSeriesRDD(dtIndex, seriesByFile)
    val cmpRdd = sc.fromRedisKeyPattern(redisNode, prefix + "_*").getRedisTimeSeriesRDD(dtIndex)
    
    averageTime(Rdd, cmpRdd, cnt, writer)

    val filterRdd = Rdd.filter(_._1.endsWith("Col1"))
    val cmpfilterRdd = sc.fromRedisKeyPattern(redisNode, prefix + "_*").getRedisTimeSeriesRDD(dtIndex).filterKeys(".*Col1")
    
    averageTime(filterRdd, cmpfilterRdd, cnt, writer)
    
    val _startTime = nextBusinessDay(new DateTime(start.getMillis + (end.getMillis - start.getMillis) * 1 / 4, UTC)).toString
    val startTime = new DateTime(_startTime.toString.substring(0, _startTime.toString.indexOf("T")), UTC)
    var filteredRddStart = filterRdd.filterStartingBefore(startTime)
    var cmpfilteredRddStart = cmpfilterRdd.filterStartingBefore(startTime)
    
    averageTime(filteredRddStart, cmpfilteredRddStart, cnt, writer)
    
    val _endTime = nextBusinessDay(new DateTime(start.getMillis + (end.getMillis - start.getMillis) * 3 / 4, UTC)).toString
    val endTime = new DateTime(_endTime.toString.substring(0, _endTime.toString.indexOf("T")), UTC)
    var filteredRddEnd = filterRdd.filterEndingAfter(endTime)
    var cmpfilteredRddEnd = cmpfilterRdd.filterEndingAfter(endTime)
    
    averageTime(filteredRddEnd, cmpfilteredRddEnd, cnt, writer)

    val _slicest = nextBusinessDay(new DateTime(start.getMillis + (end.getMillis - start.getMillis)/10, UTC)).toString
    val _sliceet = nextBusinessDay(new DateTime(start.getMillis + (end.getMillis - start.getMillis)/5, UTC)).toString
    val slicest = new DateTime(_slicest.toString.substring(0, _slicest.toString.indexOf("T")), UTC)
    val sliceet = new DateTime(_sliceet.toString.substring(0, _sliceet.toString.indexOf("T")), UTC)
    val slicedRdd = Rdd.slice(slicest, sliceet).fill("linear")
    val cmpslicedRdd = cmpRdd.slice(slicest, sliceet).fill("linear")
    
    averageTime(slicedRdd, cmpslicedRdd, cnt, writer)

    writer.write("\n\n")
    writer.flush
  }
  
  def main(args: Array[String]) {
    
    val path = "/home/hadoop/RedisLabs/TEST"
    Generate(path, 8, 512, 1024, "1981-01-01", "2016-01-01")

    val conf = new SparkConf().setAppName("test").setMaster("local")
    val sc = new SparkContext(conf)
    
    val writer = new PrintWriter(new File("result.out"))
    
    (1 to 8).foreach{ i => {
      TEST(sc, writer, 4, "TEST " + i.toString, path + "/TEST" + i.toString, "TEST" + i.toString, ("127.0.0.1", 6379)) 
    }}
    
    writer.close
  }
}