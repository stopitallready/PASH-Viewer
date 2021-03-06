/*
 *-------------------
 * The ASHDatabaseH.java is part of ASH Viewer
 *-------------------
 *
 * ASH Viewer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ASH Viewer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ASH Viewer.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2009, Alex Kardapolov, All rights reserved.
 *
 */
package org.ash.history;

import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.persist.EntityCursor;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.StoreConfig;
import org.ash.database.AshDataAccessor;
import org.ash.datamodel.*;
import org.ash.datatemp.SessionsTemp;
import org.ash.datatemp.SqlsTemp;
import org.ash.history.detail.StackedChartDetail;
import org.ash.util.Options;
import org.ash.util.Utils;
import org.jdesktop.swingx.treetable.TreeTableModel;
import org.jfree.data.xy.CategoryTableXYDataset;

import javax.swing.table.DefaultTableModel;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * The Class ASHDatabaseH (history).
 */
public class ASHDatabaseH {

    /**
     * The BDB environment.
     */
    private Environment env = null;

    /**
     * The BDB store.
     */
    private EntityStore store = null;

    /**
     * The dao.
     */
    private AshDataAccessor dao = null;

    /**
     * The BDB env config.
     */
    private EnvironmentConfig envConfig;

    /**
     * The BDB store config.
     */
    private StoreConfig storeConfig;

    /**
     * The dataset.
     */
    private CategoryTableXYDataset dataset = null;

    /**
     * The current window.
     */
    private Double currentWindow = 3900000.0;

    /**
     * The half range for one 15 sec storage
     */
    private int rangeHalf = 7500;

    /**
     * Temporary first key
     */
    private double tempFirstKey = 0.0;

    /**
     * The sqls temp.
     */
    private SqlsTemp sqlsTemp;

    /**
     * The sessions temp.
     */
    private SessionsTemp sessionsTemp;

    /**
     * The sqls temp (detail).
     */
    private SqlsTemp sqlsTempDetail;

    /**
     * The sessions temp (detail).
     */
    private SessionsTemp sessionsTempDetail;

    /**
     * The store of event Class and corresponding StackedXYAreaChartDetail object
     */
    private HashMap<String, StackedChartDetail> storeStackedXYAreaChartDetail;

    /**
     * Instantiates a new BDB database.
     */
    public ASHDatabaseH() {
    }

    /**
     * Initialize.
     *
     * @throws DatabaseException the database exception
     * @throws SQLException      the SQL exception
     * @throws IOException       Signals that an I/O exception has occurred.
     */
    public void initialize(String evnDir) throws DatabaseException, SQLException,
            IOException {

        /* Open a transactional Berkeley DB engine environment. */
        envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(false);
        envConfig.setCachePercent(10); // Set BDB cache size for history = 10%
        //envConfig.setConfigParam("je.log.fileMax", Long.toString(50 * 1024 * 1024));

		/*
		final EnvironmentConfig environmentConfig = new EnvironmentConfig();
		environmentConfig.setAllowCreate(true);
		environmentConfig.setTransactional(false);
		environmentConfig.setCachePercent(85);
		environmentConfig.setConfigParam("je.log.fileMax", Long.toString(250 * 1024 * 1024));
		environmentConfig.setConfigParam("je.evictor.lruOnly", "false");
		environmentConfig.setConfigParam("je.evictor.nodesPerScan", "100");
		environmentConfig.setConfigParam("je.cleaner.minAge", "10");
		environmentConfig.setConfigParam("je.cleaner.maxBatchFiles", "20");
		environmentConfig.setConfigParam("je.cleaner.minUtilization", "20");
		*/

        env = new Environment(new File(evnDir), envConfig);

        /* Open a transactional entity store. */
        storeConfig = new StoreConfig();
        storeConfig.setAllowCreate(true);
        storeConfig.setTransactional(false);
        storeConfig.setDeferredWrite(true);
        store = new EntityStore(env, "ash.db", storeConfig);

        /* Initialize the data access object. */
        dao = new AshDataAccessor(store);

        this.sqlsTemp = new SqlsTemp();
        this.sessionsTemp = new SessionsTemp(this.store, this.dao);

        this.sqlsTempDetail = new SqlsTemp();
        this.sessionsTempDetail = new SessionsTemp(this.store, this.dao);

        this.storeStackedXYAreaChartDetail = new HashMap<String, StackedChartDetail>();

    }

    /**
     * Gets the BDB store.
     *
     * @return the store
     */
    public EntityStore getStore() {
        return store;
    }

    /**
     * Gets the BDB dao object.
     *
     * @return the BDB dao object
     */
    public AshDataAccessor getDao() {
        return dao;
    }

    /**
     * Close BDB.
     */
    public void close() {

        if (store != null) {
            try {
                store.close();
            } catch (DatabaseException dbe) {
                System.err.println("Error closing store: " + dbe.toString());
            }
        }

        if (env != null) {
            try {
                // Finally, close environment.
                env.close();
            } catch (DatabaseException dbe) {
                System.err.println("Error closing env: " + dbe.toString());
            }
        }
    }

    /**
     * Load data to chart panel data set (preview history).
     *
     * @param _dataset the _dataset
     */
    public void loadDataToChartPanelDataSetPreview(CategoryTableXYDataset _dataset) {

        try {
            int k = 0;
            this.dataset = _dataset;
            EntityCursor<ActiveSessionHistory15> items;

            Double firstKey = 0.0;
            Double lastKey = 0.0;

            // Get max and min value of AshCalcSumByEventById115Sec
            try {
                firstKey = dao.getAshBySampleTime().sortedMap().firstKey();
                lastKey = dao.getAshBySampleTime().sortedMap().lastKey();
            } catch (Exception e) {
                firstKey = 0.0;
                lastKey = 0.0;
            }
            if (lastKey == null || firstKey == null) {
                firstKey = 0.0;
                lastKey = 0.0;
            } else {
                if (this.tempFirstKey != 0.0) {
                    if (getParameter("ASH.version").equalsIgnoreCase("9i") ||
                            getParameter("ASH.version").equalsIgnoreCase("8i")) {
                        firstKey = firstKey - 30000;
                    } else {
                        firstKey = tempFirstKey - 30000;
                    }

                } else {
                    firstKey = firstKey - 30000;
                }
                lastKey = lastKey + 30000;
            }

            double deltaKey = lastKey - firstKey;

            if (currentWindow > deltaKey || deltaKey < currentWindow * 1.5) {
                k = 1;
            } else {
                k = (int) (Math.ceil(deltaKey / currentWindow));
            }

            for (double ii = firstKey; ii < lastKey; ii += rangeHalf * k * 2) {

                /* Init temporary variables */
                double cpu = 0;
                double lwlock = 0;
                double lock = 0;
                double bufferpin = 0;
                double activity = 0;
                double extension = 0;
                double client = 0;
                double ipc = 0;
                double timeout = 0;
                double io = 0;
                int kk = 1;

                items = dao.doRangeQuery(
                        dao.getAshCalcSumByEventById115Sec(),
                        ii, true,
                        ii + rangeHalf * k * 2, false);

                /* Do a filter on Ash by SampleTime. */
                Iterator<ActiveSessionHistory15> deptIter = items.iterator();

                while (deptIter.hasNext()) {

                    ActiveSessionHistory15 ashSumMain = deptIter.next();

                    cpu = cpu + ashSumMain.getCPU();
                    lwlock = lwlock + ashSumMain.getLWLock();
                    lock = lock + ashSumMain.getLock();
                    bufferpin = bufferpin + ashSumMain.getBufferPin();
                    activity = activity + ashSumMain.getActivity();
                    extension = extension + ashSumMain.getExtension();
                    client = client + ashSumMain.getClient();
                    ipc = ipc + ashSumMain.getIPC();
                    timeout = timeout + ashSumMain.getTimeout();
                    io = io + ashSumMain.getIO();
                    kk++;
                }
                items.close();

                double tempSampleTime = ii + rangeHalf * k;

                dataset.add(tempSampleTime, cpu / kk, Options.getInstance().getResource("CPULabel.text"));
                dataset.add(tempSampleTime, io / kk, Options.getInstance().getResource("IOLabel.text"));
                dataset.add(tempSampleTime, lock / kk, Options.getInstance().getResource("LockLabel.text"));
                dataset.add(tempSampleTime, lwlock / kk, Options.getInstance().getResource("LWLockLabel.text"));
                dataset.add(tempSampleTime, bufferpin / kk, Options.getInstance().getResource("BufferPinLabel.text"));
                dataset.add(tempSampleTime, activity / kk, Options.getInstance().getResource("ActivityLabel.text"));
                dataset.add(tempSampleTime, extension / kk, Options.getInstance().getResource("ExtensionLabel.text"));
                dataset.add(tempSampleTime, client / kk, Options.getInstance().getResource("ClientLabel.text"));
                dataset.add(tempSampleTime, ipc / kk, Options.getInstance().getResource("IPCLabel.text"));
                dataset.add(tempSampleTime, timeout / kk, Options.getInstance().getResource("TimeoutLabel.text"));

            }

        } catch (DatabaseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }


    /**
     * Load data to chart panel data set (preview history).
     *
     * @param _dataset the _dataset
     */
    public void loadDataToChartPanelDataSetTA(CategoryTableXYDataset _dataset, double begin, double end) {

        try {
            int k = 0;
            this.dataset = _dataset;
            EntityCursor<ActiveSessionHistory15> items;

            // Get max and min value of AshCalcSumByEventById115Sec
            Double firstKey = begin - rangeHalf;
            Double lastKey = end + rangeHalf;

            double deltaKey = lastKey - firstKey;

            if (currentWindow > deltaKey || deltaKey < currentWindow * 1.5) {
                k = 1;
            } else {
                k = (int) (Math.ceil(deltaKey / currentWindow));
            }

            for (double ii = firstKey; ii < lastKey; ii += rangeHalf * k * 2) {

                /* Init temporary variables */
                double cpu = 0;
                double lwlock = 0;
                double lock = 0;
                double bufferpin = 0;
                double activity = 0;
                double extension = 0;
                double client = 0;
                double ipc = 0;
                double timeout = 0;
                double io = 0;

                items = dao.doRangeQuery(
                        dao.getAshCalcSumByEventById115Sec(),
                        ii, true,
                        ii + rangeHalf * k * 2, false);

                /* Do a filter on Ash by SampleTime. */
                Iterator<ActiveSessionHistory15> deptIter = items.iterator();

                while (deptIter.hasNext()) {

                    ActiveSessionHistory15 ashSumMain = deptIter.next();

                    cpu = cpu + ashSumMain.getCPU();
                    lwlock = lwlock + ashSumMain.getLWLock();
                    lock = lock + ashSumMain.getLock();
                    bufferpin = bufferpin + ashSumMain.getBufferPin();
                    activity = activity + ashSumMain.getActivity();
                    extension = extension + ashSumMain.getExtension();
                    client = client + ashSumMain.getClient();
                    ipc = ipc + ashSumMain.getIPC();
                    timeout = timeout + ashSumMain.getTimeout();
                    io = io + ashSumMain.getIO();
                }
                items.close();

                double tempSampleTime = ii + rangeHalf * 2 * k;

                dataset.add(tempSampleTime, cpu / k, Options.getInstance().getResource("CPULabel.text"));
                dataset.add(tempSampleTime, io / k, Options.getInstance().getResource("IOLabel.text"));
                dataset.add(tempSampleTime, lock / k, Options.getInstance().getResource("LockLabel.text"));
                dataset.add(tempSampleTime, lwlock / k, Options.getInstance().getResource("LWLockLabel.text"));
                dataset.add(tempSampleTime, bufferpin / k, Options.getInstance().getResource("BufferPinLabel.text"));
                dataset.add(tempSampleTime, activity / k, Options.getInstance().getResource("ActivityLabel.text"));
                dataset.add(tempSampleTime, extension / k, Options.getInstance().getResource("ExtensionLabel.text"));
                dataset.add(tempSampleTime, client / k, Options.getInstance().getResource("ClientLabel.text"));
                dataset.add(tempSampleTime, ipc / k, Options.getInstance().getResource("IPCLabel.text"));
                dataset.add(tempSampleTime, timeout / k, Options.getInstance().getResource("TimeoutLabel.text"));
            }

        } catch (DatabaseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }


    public DefaultTableModel getASHRawData(double begin, double end) throws DatabaseException {

        DefaultTableModel model = new DefaultTableModel(new String[]{
                "SampleID",
                "SampleTime",
                "PID",
                "UserID",
                "Username",
                "Program",
                "Sql type",
                "SQL ID",
                "Duration",
                "Event",
                "Wait Class",
                "Hostname",
                "Backend Type"
        }, 0);

        try {

            /* Do a filter on AshIdTime by SampleTime. (detail) */
            EntityCursor<AshIdTime> ashIdTimeCursor =
                    dao.doRangeQuery(dao.getAshBySampleTime(),
                            begin, true, end, false);

            Iterator<AshIdTime> ashIdTimeIter = ashIdTimeCursor.iterator();

            // Iterate over AshIdTime (detail)
            while (ashIdTimeIter.hasNext()) {
                AshIdTime ashIdTimeMain = ashIdTimeIter.next();

                Long sampleTimeLong = (long) ashIdTimeMain.getsampleTime();
                Date td = new Date(sampleTimeLong.longValue());
                DateFormat df = new SimpleDateFormat("dd.MM.yyyy HH.mm.ss");
                String reportDateStr = df.format(td);

                /* Do a filter on ActiveSessionHistory by SampleID (detail). */
                EntityCursor<ActiveSessionHistory> ActiveSessionHistoryCursor = dao.getActiveSessionHistoryByAshId().subIndex(ashIdTimeMain.getsampleId()).entities();
                Iterator<ActiveSessionHistory> ActiveSessionHistoryIter = ActiveSessionHistoryCursor.iterator();

                // Iterate over ActiveSessionHistory (detail)
                while (ActiveSessionHistoryIter.hasNext()) {
                    ActiveSessionHistory ASH = ActiveSessionHistoryIter.next();

                    /* Get username */
                    String username = this.getUsername(ASH.getUserId());

                    model.addRow(new Object[]{
                            ASH.getSampleId(),
                            reportDateStr,
                            ASH.getSessionId(),
                            ASH.getUserId(),
                            ASH.getUserName(),
                            ASH.getProgram(),
                            ASH.getCommand_type(),
                            ASH.getSqlId(),
                            ASH.getDuration(),
                            ASH.getEvent(),
                            ASH.getWaitClass(),
                            ASH.getHostname(),
                            ASH.getBackendType()
                    });
                }
                ActiveSessionHistoryCursor.close();
            }
            ashIdTimeCursor.close();

        } catch (Exception e) {
            e.printStackTrace();
        }

        return model;
    }


    /**
     * Gets the parameter value from local BDB.
     *
     * @param parameter parameter id
     * @return the value
     */
    public String getParameter(String parameter) {

        String value = null;
        try {
            AshParamValue ashParamValue = dao.getAshParamValue().get(parameter);
            if (ashParamValue != null) {
                value = ashParamValue.getValue();
            } else {
                value = "Unknown";
            }
        } catch (DatabaseException e) {
            // TODO Auto-generated catch block
            value = "Unknown";
            e.printStackTrace();
        }
        return value;
    }

    /**
     * @param tempFirstKey the tempFirstKey to set
     */
    public void setTempFirstKey(double tempFirstKey) {
        this.tempFirstKey = tempFirstKey;
    }

    /**
     * Calculate sqls, sessions data.
     *
     * @param beginTime the begin time
     * @param endTime   the end time
     * @param eventFlag All for main top activity, event class - for detail
     */
    public void calculateSqlsSessionsData(double beginTime, double endTime,
                                          String eventFlag) {

        try {

            SqlsTemp tmpSqlsTemp = null;
            SessionsTemp tmpSessionsTemp = null;

            if (eventFlag.equalsIgnoreCase("All")) {
                tmpSqlsTemp = getSqlsTemp();
                tmpSessionsTemp = getSessionsTemp();
            } else {
                tmpSqlsTemp = getSqlsTempDetail();
                tmpSessionsTemp = getSessionsTempDetail();
            }

            // get sample id's for beginTime and endTime
            EntityCursor<AshIdTime> ashSampleIds;
            ashSampleIds = dao.doRangeQuery(dao.getAshBySampleTime(),
                    beginTime - rangeHalf, true,
                    endTime + rangeHalf, true);
            /* Iterate on Ash by SampleTime. */
            Iterator<AshIdTime> ashIter = ashSampleIds.iterator();

            while (ashIter.hasNext()) {

                AshIdTime ashSumMain = ashIter.next();

                // get rows from ActiveSessionHistory for samplId
                EntityCursor<ActiveSessionHistory> ActiveSessionHistoryCursor;
                ActiveSessionHistoryCursor =
                        dao.doRangeQuery(dao.getActiveSessionHistoryByAshId(),
                                ashSumMain.getsampleId(), true,
                                ashSumMain.getsampleId(), true);
                Iterator<ActiveSessionHistory> ActiveSessionHistoryIter =
                        ActiveSessionHistoryCursor.iterator();

                while (ActiveSessionHistoryIter.hasNext()) {
                    ActiveSessionHistory ASH =
                            ActiveSessionHistoryIter.next();

                    // sql data
                    String sqlId = ASH.getSqlId();
                    double waitClassId = ASH.getWaitClassId();
                    // session data
                    Long sessionId = ASH.getSessionId();
                    String sessionidS = sessionId.toString().trim();
                    Long useridL = ASH.getUserId();
                    String usernameSess = ASH.getUserName();
                    String programSess = ASH.getProgram();
                    programSess = programSess + "@" + ASH.getHostname();
                    String backendSess = ASH.getBackendType();
                    String waitClass = ASH.getWaitClass();
                    String eventName = ASH.getEvent();

                    Long queryStart = ASH.getQueryStart();
                    Double duration = ASH.getDuration();

                    // Exit when current eventClas != eventFlag
                    if (!eventFlag.equalsIgnoreCase("All")) {
                        if (waitClass != null && waitClass.equalsIgnoreCase(eventFlag)) {
                            this.loadDataToTempSqlSession(tmpSqlsTemp, tmpSessionsTemp,
                                    sqlId, waitClassId, sessionId, sessionidS,
                                    0.0, backendSess, useridL, usernameSess, programSess,
                                    true, eventName, queryStart, duration);
                        }
                    } else {
                        this.loadDataToTempSqlSession(tmpSqlsTemp, tmpSessionsTemp,
                                sqlId, waitClassId, sessionId, sessionidS,
                                0.0, backendSess, useridL, usernameSess, programSess,
                                false, eventFlag, queryStart, duration);
                    }
                }
                // Close cursor!!
                ActiveSessionHistoryCursor.close();
            }
            tmpSqlsTemp.set_sum();
            tmpSessionsTemp.set_sum();
            // Close cursor!!
            ashSampleIds.close();

        } catch (DatabaseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /**
     * Get sql_type for sql_id
     *
     * @param sqlId
     * @return sqlType
     */
    public String getSqlType(String sqlId) {
        String sqlType = null;
        try {
            AshSqlIdTypeText ash = dao.getAshSqlIdTypeTextId().get(sqlId);
            if (ash != null) {
                sqlType = ash.getCommandType();
            } else {
                sqlType = "";
            }
        } catch (DatabaseException e) {
            // TODO Auto-generated catch block
            sqlType = "";
            e.printStackTrace();
        }
        return sqlType;
    }

    /**
     * Get sql_text for sql_id
     *
     * @param sqlId
     * @return sqlType
     */
    public String getSqlText(String sqlId) {
        String sqlText = null;
        try {
            AshSqlIdTypeText ash = dao.getAshSqlIdTypeTextId().get(sqlId);
            if (ash != null) {
                sqlText = ash.getSqlText();
            } else {
                sqlText = "";
            }

        } catch (DatabaseException e) {
            // TODO Auto-generated catch block
            sqlText = "";
            e.printStackTrace();
        }
        return sqlText;
    }

    /**
     * Load data to temporary sql and sessions (gantt data)
     *
     * @param tmpSqlsTemp
     * @param tmpSessionsTemp
     * @param sqlId
     * @param waitClassId
     * @param sessionId
     * @param sessionidS
     * @param sessionSerial
     * @param sessioniSerialS
     * @param useridL
     * @param programSess
     * @param isDetail
     */
    private void loadDataToTempSqlSession(SqlsTemp tmpSqlsTemp, SessionsTemp tmpSessionsTemp,
                                          String sqlId,
                                          double waitClassId, Long sessionId, String sessionidS, Double sessionSerial,
                                          String backendType, Long useridL, String usernameSess, String programSess,
                                          boolean isDetail, String eventDetail, Long queryStart, Double duration) {

        int count = 1;

        /** Save data for sql row */
        if (sqlId != null && !sqlId.equalsIgnoreCase("0")) {
            // Save SQL_ID and init
            tmpSqlsTemp.setSqlId(sqlId);
            // Save group event
            tmpSqlsTemp.setTimeOfGroupEvent(sqlId, waitClassId, count);
	    tmpSqlsTemp.setSqlStats(sqlId, sessionId, queryStart, duration);
        }

        /** Save data for session row */
        tmpSessionsTemp.setSessionId(sessionidS, backendType, programSess, "", usernameSess);
        tmpSessionsTemp.setTimeOfGroupEvent(sessionidS, waitClassId, count);

        /** Save event detail data for sql and sessions row */
        if (isDetail) {
            if (sqlId != null && !sqlId.equalsIgnoreCase("0")) {
                tmpSqlsTemp.setTimeOfEventName(
                        sqlId,
                        waitClassId,
                        eventDetail,
                        count);
            }
            tmpSessionsTemp.setTimeOfEventName(
                    sessionidS,
                    waitClassId,
                    eventDetail,
                    count);
        }
    }


    /**
     * Save reference to StackedXYAreaChartDetail for wait classes or cpu used
     *
     * @param detailValue  StackedChartDetail object
     * @param waitClasskey
     */
    public void saveStackedXYAreaChartDetail(StackedChartDetail detailValue,
                                             String waitClasskey) {
        this.storeStackedXYAreaChartDetail.put(waitClasskey, detailValue);
    }

    /**
     * Clear reference to StackedXYAreaChartDetail for wait classes or cpu used
     */
    public void clearStackedXYAreaChartDetail() {
        this.storeStackedXYAreaChartDetail.clear();
    }


    /**
     * Load data to chart panel dataset (detail charts).
     */
    public void initialLoadingDataToChartPanelDataSetDetail
    (String waitClass, double beginTime, double endTime) {
        this.loadDataToChartPanelDataSetDetail(waitClass, beginTime, endTime);
    }


    /**
     * Load data to chart panel dataset (detail charts).
     */
    private void loadDataToChartPanelDataSetDetail(String waitClass,
                                                   double begin, double end) {

        try {

            String cpuUsed = Options.getInstance().getResource("cpuLabel.text");
            int k = 0;
            boolean isWaitClassCpu = waitClass.equalsIgnoreCase(cpuUsed);
            boolean isAddPointsToLeftSide = false;

            this.initSeriesPaint();

            // Get max and min value of AshCalcSumByEventById115Sec
            Double firstKey = begin - rangeHalf;
            Double lastKey = end + rangeHalf;

            double deltaKey = lastKey - firstKey;

            if (currentWindow > deltaKey || deltaKey < currentWindow * 1.5) {
                k = 1;
            } else {
                k = (int) (Math.ceil(deltaKey / currentWindow));
            }

            for (double dd = firstKey; dd < lastKey; dd += rangeHalf * k * 2) {

                /* Do a filter on AshIdTime by SampleTime. (detail) */
                EntityCursor<AshIdTime> ashIdTimeCursor = dao.doRangeQuery(dao
                                .getAshBySampleTime(), dd, true, dd + rangeHalf * k * 2,
                        false);

                Iterator<AshIdTime> ashIdTimeIter = ashIdTimeCursor.iterator();

                // Iterate over AshIdTime (detail)
                while (ashIdTimeIter.hasNext()) {
                    AshIdTime ashIdTimeMain = ashIdTimeIter.next();

                    /* Do a filter on ActiveSessionHistory by SampleID (detail). */
                    EntityCursor<ActiveSessionHistory> ActiveSessionHistoryCursor = dao
                            .getActiveSessionHistoryByAshId().subIndex(
                                    ashIdTimeMain.getsampleId()).entities();
                    Iterator<ActiveSessionHistory> ActiveSessionHistoryIter = ActiveSessionHistoryCursor
                            .iterator();

                    // Iterate over ActiveSessionHistory (detail)
                    while (ActiveSessionHistoryIter.hasNext()) {
                        ActiveSessionHistory ASH = ActiveSessionHistoryIter
                                .next();
                        double count = 1.0;


                        // If waitclass is empty - go to next row
                        if (ASH.getWaitClass() == null
                                || ASH.getWaitClass().equals("")) {
                            continue;
                        }

                        String eventName = ASH.getEvent();
                        String waitClassEvent = ASH.getWaitClass();
                        StackedChartDetail tmpStackedObj =
                                this.storeStackedXYAreaChartDetail.get(waitClassEvent);

                        // Exit
                        if (tmpStackedObj == null)
                            continue;

                        // Checking and loading data
                        if (tmpStackedObj.isSeriesContainName(eventName)) {
                            double tmp = tmpStackedObj
                                    .getSeriesNameSum(eventName);
                            tmpStackedObj.setSeriesNameSum(eventName, tmp
                                    + count);
                        } else {
                            int nextNumber = 0;
                            if (!tmpStackedObj.isSeriesIdNameEmpty()) {
                                nextNumber = tmpStackedObj
                                        .getSizeSeriesIdName();
                                tmpStackedObj.setSeriesIdName(nextNumber, eventName);
                                tmpStackedObj.setSeriesNameSum(eventName, 1.0);
                                tmpStackedObj.setSeriesPaint(nextNumber, eventName, "loadDataToChartPanelDataSetDetail");

                            } else {
                                tmpStackedObj.setSeriesIdName(0, eventName);
                                tmpStackedObj.setSeriesNameSum(eventName, 1.0);
                                tmpStackedObj.setSeriesPaint(0, eventName, "initSeriesPaint");
                                isAddPointsToLeftSide = true;
                            }
                        }

                    }
                    ActiveSessionHistoryCursor.close();
                }
                ashIdTimeCursor.close();

                // Calculate, save and clear
                Set entriesSum = this.storeStackedXYAreaChartDetail.entrySet();
                Iterator iterSum = entriesSum.iterator();
                while (iterSum.hasNext()) {
                    Map.Entry entry = (Map.Entry) iterSum.next();
                    String keyClass = (String) entry.getKey();
                    StackedChartDetail valueChart = (StackedChartDetail) entry
                            .getValue();
                    valueChart.calcSaveAndClear(rangeHalf * k, dd);

                    // Add points to left side
                    if (isAddPointsToLeftSide) {
                        valueChart.addPointsToLeft(begin, dd, rangeHalf * k);
                        isAddPointsToLeftSide = false;
                    }

                }
            }

        } catch (DatabaseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /**
     * Initialize series paint, renderer, dataset for stacked charts.
     */
    private void initSeriesPaint() {

        String cpuUsed = Options.getInstance().getResource("cpuLabel.text");
        Set entries = this.storeStackedXYAreaChartDetail.entrySet();
        Iterator iter = entries.iterator();

        while (iter.hasNext()) {

            Map.Entry entry = (Map.Entry) iter.next();
            String keyClass = (String) entry.getKey();

            StackedChartDetail valueChart = (StackedChartDetail) entry
                    .getValue();

            if (keyClass.equalsIgnoreCase(cpuUsed)) { // CPU used
                valueChart.setSeriesIdName(0, cpuUsed);
                valueChart.setSeriesNameSum(cpuUsed, 0.0);
                valueChart.setSeriesPaint(0, cpuUsed, "initSeriesPaint");
            }
        }
    }

    /**
     * Gets the max/min value of time period BDB.
     *
     * @param maxMin 1 - begin of time period, 0 - end of time period
     * @return the value
     */
    public Double getMaxMinTimePeriod(int maxMin) {
        Double value = 0.0;
	try {
	        if (maxMin == 1) { // Begin
        	    value = dao.getAshCalcSumByEventById115Sec().sortedMap().firstKey();
	        } else { // End
        	    value = dao.getAshCalcSumByEventById115Sec().sortedMap().lastKey();
	        }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return value;
    }

    /**
     * Gets the username from local BDB.
     *
     * @param userId the user id
     * @return the username
     */
    public String getUsername(Long userId) {

        String userName = null;
        try {
            AshUserIdUsername userIdU = dao.getUserIdUsernameById().get(userId);
            if (userIdU != null) {
                userName = userIdU.getUsername();
            } else {
                userName = "";
            }
        } catch (DatabaseException e) {
            // TODO Auto-generated catch block
            userName = "";
            e.printStackTrace();
        }
        return userName;
    }

    /**
     * Delete data from database.
     *
     * @param start
     * @param end
     */
    public void deleteData(long start, long end) {
        dao.deleteData(start, end);
    }

    /**
     * Delete all data from database.
     */
    public void deleteAllData() {
        //env.removeDatabase(txn, databaseName)
        try {
            store.truncateClass(ActiveSessionHistory.class);
            store.truncateClass(ActiveSessionHistory15.class);
            store.truncateClass(AshIdTime.class);
        } catch (DatabaseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /**
     * DB env log cleaning.
     */
    public void cleanLogs() {
        boolean anyCleaned = false;
        try {
            while (env.cleanLog() > 0) {
                anyCleaned = true;
            }
        } catch (DatabaseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        if (anyCleaned) {
            CheckpointConfig force = new CheckpointConfig();
            force.setForce(true);
            try {
                env.checkpoint(force);
            } catch (DatabaseException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    /**
     * Gets the sqls temp.
     *
     * @return the sqls temp
     */
    public SqlsTemp getSqlsTemp() {
        return sqlsTemp;
    }

    /**
     * Gets the sessions temp.
     *
     * @return the sessions temp
     */
    public SessionsTemp getSessionsTemp() {
        return sessionsTemp;
    }

    /**
     * Gets the sqls temp.
     *
     * @return the sqls temp
     */
    public SqlsTemp getSqlsTempDetail() {
        return sqlsTempDetail;
    }

    /**
     * Gets the sessions temp.
     *
     * @return the sessions temp
     */
    public SessionsTemp getSessionsTempDetail() {
        return sessionsTempDetail;
    }

    /**
     * @return the tempFirstKey
     */
    public double getTempFirstKey() {
        return tempFirstKey;
    }

}
