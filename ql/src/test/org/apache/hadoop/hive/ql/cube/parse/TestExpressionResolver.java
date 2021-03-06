package org.apache.hadoop.hive.ql.cube.parse;
/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
*/

import static org.apache.hadoop.hive.ql.cube.parse.CubeTestSetup.getDbName;
import static org.apache.hadoop.hive.ql.cube.parse.CubeTestSetup.getExpectedQuery;
import static org.apache.hadoop.hive.ql.cube.parse.CubeTestSetup.getWhereForDailyAndHourly2days;
import static org.apache.hadoop.hive.ql.cube.parse.CubeTestSetup.getWhereForHourly2days;
import static org.apache.hadoop.hive.ql.cube.parse.CubeTestSetup.twoDaysRange;

import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.cube.metadata.StorageConstants;
import org.apache.hadoop.hive.ql.parse.ParseException;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestExpressionResolver {

  private Configuration conf;
  private CubeQueryRewriter driver;
  private final String cubeName = CubeTestSetup.TEST_CUBE_NAME;

  static CubeTestSetup setup;
  static HiveConf hconf = new HiveConf(TestExpressionResolver.class);
  static String dbName;
  @BeforeClass
  public static void setup() throws Exception {
    SessionState.start(hconf);
    setup = new CubeTestSetup();
    String dbName = TestExpressionResolver.class.getSimpleName();
    setup.createSources(hconf, dbName);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    setup.dropSources(hconf);
  }

  @Before
  public void setupDriver() throws Exception {
    conf = new Configuration();
    conf.set(CubeQueryConfUtil.DRIVER_SUPPORTED_STORAGES, "C1,C2");
    conf.setBoolean(CubeQueryConfUtil.DISABLE_AUTO_JOINS, false);
    conf.setBoolean(CubeQueryConfUtil.ENABLE_SELECT_TO_GROUPBY, true);
    conf.setBoolean(CubeQueryConfUtil.ENABLE_GROUP_BY_TO_SELECT, true);
    conf.setBoolean(CubeQueryConfUtil.DISABLE_AGGREGATE_RESOLVER, false);
    driver = new CubeQueryRewriter(new HiveConf(conf, HiveConf.class));
  }

  @Test
  public void testColumnErrors() throws Exception {
    SemanticException th = null;
    try {
      rewrite(driver, "select nocolexpr, SUM(msr2) from testCube" +
        " where " + twoDaysRange);
    } catch (SemanticException e) {
      th = e;
      e.printStackTrace();
    }
    Assert.assertNotNull(th);
    Assert.assertEquals(th.getCanonicalErrorMsg().getErrorCode(),
        ErrorMsg.COLUMN_NOT_FOUND.getErrorCode());
    Assert.assertTrue(th.getMessage().contains("nonexist"));

    th = null;
    try {
      rewrite(driver, "select invalidexpr, SUM(msr2) from testCube" +
        " where " + twoDaysRange);
    } catch (SemanticException e) {
      th = e;
      e.printStackTrace();
    }
    Assert.assertNotNull(th);
    Assert.assertEquals(th.getCanonicalErrorMsg().getErrorCode(),
        ErrorMsg.COLUMN_NOT_FOUND.getErrorCode());
    Assert.assertTrue(th.getMessage().contains("invalidexpr"));

    // Query with column life not in the range
    th = null;
    try {
      rewrite(driver, "cube select newexpr, SUM(msr2) from testCube" +
        " where " + twoDaysRange);
    } catch (SemanticException e) {
      th = e;
      e.printStackTrace();
    }
    Assert.assertNotNull(th);
    Assert.assertEquals(th.getCanonicalErrorMsg().getErrorCode(),
        ErrorMsg.NOT_AVAILABLE_IN_RANGE.getErrorCode());
  }

  private CubeQueryContext rewrittenQuery;
  private String rewrite(CubeQueryRewriter driver, String query)
    throws SemanticException, ParseException {
    rewrittenQuery = driver.rewrite(query);
    return rewrittenQuery.toHQL();
  }

  @Test
  public void testCubeQuery() throws Exception {
    // select with expression
    String hqlQuery = rewrite(driver, "cube select" +
        " avgmsr from testCube where " + twoDaysRange);
    String expected = getExpectedQuery(cubeName,
        "select avg(testCube.msr1 + testCube.msr2) FROM ", null, null,
        getWhereForHourly2days("C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    hqlQuery = rewrite(driver, "select dim1, roundedmsr2 from testCube" +
        " where " + twoDaysRange);
    expected = getExpectedQuery(cubeName,
        "select testcube.dim1, round(sum(testcube.msr2)/1000) FROM ", null,
        " group by testcube.dim1",
        getWhereForDailyAndHourly2days(cubeName, "c1_summary1"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    // where with expression
    hqlQuery = rewrite(driver, "select msr2 from testCube" +
        " where " + twoDaysRange + " and substrexpr != 'XYZ'");
    expected = getExpectedQuery(cubeName,
        "select sum(testcube.msr2) FROM ", null, " and substr(testCube.dim1, 3) != 'XYZ'",
        getWhereForDailyAndHourly2days(cubeName, "c1_summary1"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    hqlQuery = rewrite(driver, "select SUM(msr2) from testCube" +
        " where substrexpr != 'XYZ' and " + twoDaysRange );
    expected = getExpectedQuery(cubeName,
        "select sum(testcube.msr2) FROM ", "substr(testCube.dim1, 3) != 'XYZ'", null,
        getWhereForDailyAndHourly2days(cubeName, "c1_summary1"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    // expression in select and where
    hqlQuery = rewrite(driver, "select avgmsr from testCube" +
        " where " + twoDaysRange + " and substrexpr != 'XYZ'");
    expected = getExpectedQuery(cubeName,
        "select avg(testCube.msr1 + testCube.msr2) FROM ",
        null, " and substr(testCube.dim1, 3) != 'XYZ'",
        getWhereForHourly2days("C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    hqlQuery = rewrite(driver, "select avgmsr from testCube" +
        " where " + twoDaysRange + " and indiasubstr = true");
    expected = getExpectedQuery(cubeName,
        "select avg(testCube.msr1 + testCube.msr2) FROM ",
        null, " and (substr(testCube.dim1, 3) = 'INDIA') = true",
        getWhereForHourly2days("C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    // expression with alias
    hqlQuery = rewrite(driver, "select TC.avgmsr from testCube TC" +
        " where " + twoDaysRange + " and TC.substrexpr != 'XYZ'");
    expected = getExpectedQuery("tc",
        "select avg(tc.msr1 + tc.msr2) FROM ", null, " and substr(tc.dim1, 3) != 'XYZ'",
        getWhereForHourly2days("tc", "C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    // expression with column alias
    hqlQuery = rewrite(driver, "select TC.substrexpr as subdim1, TC.avgmsr from testCube TC" +
        " where " + twoDaysRange + " and subdim1 != 'XYZ'");
    expected = getExpectedQuery("tc",
        "select substr(tc.dim1, 3) subdim1, avg(tc.msr1 + tc.msr2) FROM ",
        null, " and subdim1 != 'XYZ' group by substr(tc.dim1, 3)",
        getWhereForHourly2days("tc", "C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    // expression with groupby
    hqlQuery = rewrite(driver, "select avgmsr from testCube" +
        " where " + twoDaysRange + " and substrexpr != 'XYZ' group by booleancut");
    expected = getExpectedQuery(cubeName,
        "select testCube.dim1 != 'x' AND testCube.dim2 != 10 ," +
        " avg(testCube.msr1 + testCube.msr2) FROM ", null,
        " and substr(testCube.dim1, 3) != 'XYZ'" +
        " group by testCube.dim1 != 'x' AND testCube.dim2 != 10",
        getWhereForHourly2days("C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    hqlQuery = rewrite(driver, "select booleancut, avgmsr from testCube" +
        " where " + twoDaysRange + " and substrexpr != 'XYZ'");
    expected = getExpectedQuery(cubeName,
        "select testCube.dim1 != 'x' AND testCube.dim2 != 10 ," +
        " avg(testCube.msr1 + testCube.msr2) FROM ", null,
        " and substr(testCube.dim1, 3) != 'XYZ' " +
        "group by testCube.dim1 != 'x' AND testCube.dim2 != 10",
        getWhereForHourly2days("C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    // expression which results in join
    hqlQuery = rewrite(driver, "select cityAndState, avgmsr from testCube" +
        " where " + twoDaysRange + " and substrexpr != 'XYZ'");

    String joinExpr = "join " + getDbName() + "c1_citytable citydim" +
        " on testcube.cityid = citydim.id and (citydim.dt = 'latest') join" +
        getDbName() + "c1_statetable statedim on" +
        " testcube.stateid = statedim.id and (statedim.dt = 'latest')";
    expected = getExpectedQuery(cubeName,
      "select concat(citydim.name, \":\", statedim.name)," +
      " avg(testcube.msr1 + testcube.msr2) FROM ", joinExpr,
      null, " and substr(testcube.dim1, 3) != 'XYZ'" +
      " group by concat(citydim.name, \":\", statedim.name)", null,
      getWhereForHourly2days("C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    hqlQuery = rewrite(driver, "select cityAndState, avgmsr from testCube tc" +
        " join citydim cd join statedim sd " +
        " where " + twoDaysRange + " and substrexpr != 'XYZ'");

    joinExpr = " inner join " + getDbName() + "c1_citytable cd" +
        " on tc.cityid = cd.id and (cd.dt = 'latest') inner join" +
        getDbName() + "c1_statetable sd on" +
        " tc.stateid = sd.id and (sd.dt = 'latest')";
    expected = getExpectedQuery("tc",
      "select concat(cd.name, \":\", sd.name)," +
      " avg(tc.msr1 + tc.msr2) FROM ", joinExpr,
      null, " and substr(tc.dim1, 3) != 'XYZ'" +
      " group by concat(cd.name, \":\", sd.name)", null,
      getWhereForHourly2days("tc", "C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    // expression in join clause
    List<String> joinWhereConds = new ArrayList<String>();
    joinWhereConds.add(StorageUtil.getWherePartClause("dt",
      "statedim", StorageConstants.getPartitionsForLatest()));
    hqlQuery = rewrite(driver, "select cityAndState, avgmsr from testCube " +
        " join citydim on substrexpr != 'XYZ' where " + twoDaysRange);

    joinExpr = " inner join " + getDbName() + "c1_citytable citydim" +
        " on testcube.cityid = citydim.id " +
        " and substr(testcube.dim1, 3) != 'XYZ' and (citydim.dt = 'latest') join" +
        getDbName() + "c1_statetable statedim on" +
        " testcube.stateid = statedim.id ";
    expected = getExpectedQuery(cubeName,
      "select concat(citydim.name, \":\", statedim.name)," +
      " avg(testcube.msr1 + testcube.msr2) FROM ", joinExpr,
      null, " group by concat(citydim.name, \":\", statedim.name)", joinWhereConds,
      getWhereForHourly2days("C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    // expression with having clause
    hqlQuery = rewrite(driver, "cube select booleancut, avgmsr from testCube" +
        " where " + twoDaysRange + " and substrexpr != 'XYZ'" +
        " having msr6 > 100.0");
    expected = getExpectedQuery(cubeName,
        "select testCube.dim1 != 'x' AND testCube.dim2 != 10 ," +
        " avg(testCube.msr1 + testCube.msr2) FROM ", null,
        " and substr(testCube.dim1, 3) != 'XYZ' " +
        " group by testCube.dim1 != 'x' AND testCube.dim2 != 10" +
        " having (sum(testCube.msr2) + max(testCube.msr3))/ count(testcube.msr4) > 100.0",
        getWhereForHourly2days("C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    // expression with orderby clause
    hqlQuery = rewrite(driver, "cube select avgmsr from testCube " +
        " where " + twoDaysRange + " and substrexpr != 'XYZ'" +
        " group by booleancut having msr6 > 100.0 order by booleancut");
    expected = getExpectedQuery(cubeName,
        "select testCube.dim1 != 'x' AND testCube.dim2 != 10 ," +
        " avg(testCube.msr1 + testCube.msr2) FROM ", null,
        " and substr(testCube.dim1, 3) != 'XYZ' " +
        " group by testCube.dim1 != 'x' AND testCube.dim2 != 10" +
        " having (sum(testCube.msr2) + max(testCube.msr3))/ count(testcube.msr4) > 100.0" +
        " order by testCube.dim1 != 'x' AND testCube.dim2 != 10 asc",
        getWhereForHourly2days("C1_testfact2_raw"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    hqlQuery = rewrite(driver, "cube select booleancut bc, msr2 from testCube"
        + " where " + twoDaysRange + " and substrexpr != 'XYZ'" +
        " having msr6 > 100.0 order by bc");
    expected = getExpectedQuery(cubeName,
        "select testCube.dim1 != 'x' AND testCube.dim2 != 10 bc," +
        " sum(testCube.msr2) FROM ", null,
        " and substr(testCube.dim1, 3) != 'XYZ' " +
        " group by testCube.dim1 != 'x' AND testCube.dim2 != 10" +
        " having (sum(testCube.msr2) + max(testCube.msr3))/ count(testcube.msr4) > 100.0"+
        " order by bc asc",
        getWhereForDailyAndHourly2days(cubeName, "c1_summary2"));
    TestCubeRewriter.compareQueries(expected, hqlQuery);
  }

  @Test
  public void testDerivedCube() throws SemanticException, ParseException {
    SemanticException th = null;
    try {
      rewrite(driver, "select avgmsr from derivedCube" +
          " where " + twoDaysRange);
    } catch (SemanticException e) {
      th = e;
      e.printStackTrace();
    }
    Assert.assertNotNull(th);
    Assert.assertEquals(th.getCanonicalErrorMsg().getErrorCode(),
        ErrorMsg.COLUMN_NOT_FOUND.getErrorCode());
  }

  @Test
  public void testDimensionQuery() throws Exception {
    String hqlQuery = rewrite(driver, "select citydim.name, cityaddress from" +
      " citydim");
    
    String joinExpr = "join TestExpressionResolver.c1_ziptable zipdim on" +
        " citydim.zipcode = zipdim.code and (zipdim.dt = 'latest')" +
        " join TestExpressionResolver.c1_statetable statedim on" +
        " citydim.stateid = statedim.id and (statedim.dt = 'latest')" +
        " join TestExpressionResolver.c1_countrytable countrydim on" +
        " statedim.countryid = countrydim.id";

    String expected = getExpectedQuery("citydim",
        "SELECT citydim.name, concat((citydim.name), \":\", (statedim.name )," +
        " \":\",(countrydim.name),  \":\" , ( zipdim . code )) FROM ", joinExpr,
        null, null, "c1_citytable", true);
    TestCubeRewriter.compareQueries(expected, hqlQuery);

    hqlQuery = rewrite(driver, "select ct.name, ct.cityaddress from" +
        " citydim ct");

    joinExpr = "join TestExpressionResolver.c1_ziptable zipdim on " +
        "ct.zipcode = zipdim.code and (zipdim.dt = 'latest')" +
        " join TestExpressionResolver.c1_statetable statedim on " +
        "ct.stateid = statedim.id and (statedim.dt = 'latest')" +
        " join TestExpressionResolver.c1_countrytable countrydim on " +
        "statedim.countryid = countrydim.id";

    expected = getExpectedQuery("ct",
        "SELECT ct.name, concat((ct.name), \":\", (statedim.name )," +
        " \":\",(countrydim.name),  \":\" , ( zipdim . code )) FROM ", joinExpr,
        null, null, "c1_citytable", true);
    TestCubeRewriter.compareQueries(expected, hqlQuery);
  }
}
