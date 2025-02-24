/**
 * Copyright 2017 StreamSets Inc.
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
package com.streamsets.pipeline.stage.bigquery.origin;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.QueryResponse;
import com.google.cloud.bigquery.QueryResult;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.sdk.SourceRunner;
import com.streamsets.pipeline.sdk.StageRunner;
import com.streamsets.pipeline.stage.bigquery.lib.BigQueryDelegate;
import com.streamsets.pipeline.stage.bigquery.lib.Errors;
import com.streamsets.pipeline.stage.bigquery.lib.TestBigQueryDelegate;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(value = Parameterized.class)
@PrepareForTest({ServiceAccountCredentials.class, BigQueryDelegate.class})
public class TestBigQuerySource {
  private BigQuery mockBigquery;
  private JobId jobId;
  private QueryResult mockResult;

  @Parameterized.Parameters
  public static Collection<Object[]> streams() {
    // useLegacySql
    return Arrays.asList(new Object[][]{
        {true}, {false}
    });
  }

  @Parameterized.Parameter
  public boolean useLegacySql;

  @Before
  public void setUp() throws Exception {
    mockBigquery = mock(BigQuery.class);
    jobId = JobId.of("test-project", "datacollector");
    mockResult = mock(QueryResult.class);

    mockStatic(ServiceAccountCredentials.class);
    ServiceAccountCredentials serviceAccountCredentials = mock(ServiceAccountCredentials.class);
    when(ServiceAccountCredentials.fromStream(any())).thenReturn(serviceAccountCredentials);
  }

  @Test
  public void testMissingCredentialsFile() throws Exception {
    BigQuerySourceConfig conf = new BigQuerySourceConfig();
    conf.credentials.projectId = "test";
    conf.credentials.path = "/bogus";

    BigQuerySource bigquerySource = new BigQuerySource(conf);
    SourceRunner runner = new SourceRunner.Builder(BigQueryDSource.class, bigquerySource)
        .addOutputLane("lane")
        .build();

    List<Stage.ConfigIssue> issues = runner.runValidateConfigs();
    assertEquals(1, issues.size());
    assertTrue(issues.get(0).toString().contains(Errors.BIGQUERY_04.getCode()));
  }

  @Test
  public void testProduce() throws Exception {
    File tempFile = File.createTempFile("gcp", "json");
    tempFile.deleteOnExit();

    BigQuerySourceConfig conf = new BigQuerySourceConfig();
    conf.credentials.projectId = "test";
    conf.credentials.path = tempFile.getAbsolutePath();
    conf.query = "SELECT * FROM [test:table]";

    QueryResponse mockQueryResponse = mock(QueryResponse.class);
    // First pretend we haven't finished running the query, second time around its completed.
    when(mockQueryResponse.jobCompleted()).thenReturn(false).thenReturn(true);
    when(mockQueryResponse.getJobId()).thenReturn(jobId);
    when(mockQueryResponse.hasErrors()).thenReturn(false);

    when(mockBigquery.query(any())).thenReturn(mockQueryResponse);
    when(mockBigquery.getQueryResults(jobId)).thenReturn(mockQueryResponse);

    QueryResult mockQueryResult = mock(QueryResult.class);
    when(mockQueryResponse.getResult()).thenReturn(mockQueryResult);

    List<List<FieldValue>> resultSet = new ArrayList<>(1);
    resultSet.add(TestBigQueryDelegate.createTestValues());

    when(mockResult.getSchema()).thenReturn(TestBigQueryDelegate.createTestSchema());
    when(mockResult.getValues()).thenReturn(resultSet);

    BigQuerySource bigquerySource = spy(new BigQuerySource(conf));
    doReturn(mockBigquery).when(bigquerySource).getBigQuery(any());
    doReturn(mockResult).when(bigquerySource).runQuery(any());

    SourceRunner runner = new SourceRunner.Builder(BigQueryDSource.class, bigquerySource)
        .addOutputLane("lane")
        .build();

    try {
      runner.runInit();

      StageRunner.Output output = runner.runProduce(null, 1000);
      List<Record> records = output.getRecords().get("lane");
      assertEquals(1, records.size());
      assertEquals(10, records.get(0).get().getValueAsListMap().size());
      assertEquals("nested string", records.get(0).get("/j/x").getValueAsString());
      assertEquals("z", records.get(0).get("/j/y/z").getValueAsString());
    } finally {
      runner.runDestroy();
    }
  }
}