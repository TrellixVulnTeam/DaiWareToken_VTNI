package io.pipeline.prediction.jvm

import scala.collection.JavaConversions.mapAsJavaMap

import com.netflix.hystrix.HystrixCommand
import com.netflix.hystrix.HystrixCommandGroupKey
import com.netflix.hystrix.HystrixCommandKey
import com.netflix.hystrix.HystrixCommandProperties
import com.netflix.hystrix.HystrixThreadPoolKey
import com.netflix.hystrix.HystrixThreadPoolProperties

class JavaSourceCodeEvaluationCommand(modelType: String,
                                      modelName: String,                                       
                                      predictor: Predictable, 
                                      inputs: Map[String, Any],
                                      fallback: String, 
                                      timeout: Int, 
                                      concurrencyPoolSize: Int, 
                                      rejectionThreshold: Int)
  extends HystrixCommand[String](
    HystrixCommand.Setter
      .withGroupKey(HystrixCommandGroupKey.Factory.asKey(modelType + "_" + modelName))
      .andCommandKey(HystrixCommandKey.Factory.asKey(modelType + "_" + modelName))
      .andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey(modelType + "_" + modelName))
      .andCommandPropertiesDefaults(
        HystrixCommandProperties.Setter()
         .withExecutionTimeoutInMilliseconds(timeout)
         .withExecutionIsolationStrategy(HystrixCommandProperties.ExecutionIsolationStrategy.SEMAPHORE)
         .withExecutionIsolationSemaphoreMaxConcurrentRequests(concurrencyPoolSize)
         .withFallbackIsolationSemaphoreMaxConcurrentRequests(rejectionThreshold)
      )
      .andThreadPoolPropertiesDefaults(
        HystrixThreadPoolProperties.Setter()
          .withCoreSize(concurrencyPoolSize)
          .withQueueSizeRejectionThreshold(rejectionThreshold)
      )
    )
{
  def run(): String = {
    try{
      s"""${predictor.predict(inputs)}"""
    } catch { 
       case e: Throwable => {
         System.out.println(e)
         throw e
       }
    }
  }

  override def getFallback(): String = {
    s"""${fallback}"""
  }
}
