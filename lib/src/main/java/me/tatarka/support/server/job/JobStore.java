/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package me.tatarka.support.server.job;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.util.AtomicFile;
import android.util.Log;
import android.util.Pair;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import me.tatarka.support.internal.util.ArraySet;
import me.tatarka.support.internal.util.FastXmlSerializer;
import me.tatarka.support.job.JobInfo;
import me.tatarka.support.server.job.controllers.JobStatus;
import me.tatarka.support.os.PersistableBundle;
import me.tatarka.support.server.IoThread;

/**
 * Maintains the master list of jobs that the job scheduler is tracking. These jobs are compared by
 * reference, so none of the functions in this class should make a copy.
 * Also handles read/write of persisted jobs.
 * <p/>
 * Note on locking:
 * All callers to this class must <strong>lock on the class object they are calling</strong>.
 * This is important b/c {@link WriteJobsMapToDiskRunnable}
 * and {@link ReadJobMapFromDiskRunnable} lock on that
 * object.
 */
public class JobStore {
    private static final String TAG = "JobStore";

    /**
     * Threshold to adjust how often we want to write to the db.
     */
    private static final int MAX_OPS_BEFORE_WRITE = 1;
    final ArraySet<JobStatus> mJobSet;
    final Context mContext;

    private int mDirtyOperations;

    private static final Object sSingletonLock = new Object();
    private final AtomicFile mJobsFile;
    /**
     * Handler backed by IoThread for writing to disk.
     */
    private final Handler mIoHandler = IoThread.getHandler();
    private static JobStore sSingleton;

    public static JobStore initAndGet(Context context) {
        synchronized (sSingletonLock) {
            if (sSingleton == null) {
                sSingleton = new JobStore(context, context.getFilesDir());
            }
            return sSingleton;
        }
    }

    /**
     * Construct the instance of the job store. This results in a blocking read from disk.
     */
    private JobStore(Context context, File dataDir) {
        mContext = context;
        mDirtyOperations = 0;

        File systemDir = new File(dataDir, "system");
        File jobDir = new File(systemDir, "job");
        jobDir.mkdirs();
        mJobsFile = new AtomicFile(new File(jobDir, "jobs.xml"));

        mJobSet = new ArraySet<JobStatus>();

        readJobMapFromDisk(mJobSet);
    }

    /**
     * Add a job to the master list, persisting it if necessary. If the JobStatus already exists,
     * it will be replaced.
     *
     * @param jobStatus Job to add.
     * @return Whether or not an equivalent JobStatus was replaced by this operation.
     */
    public boolean add(JobStatus jobStatus) {
        boolean replaced = mJobSet.remove(jobStatus);
        mJobSet.add(jobStatus);
        if (!jobStatus.isPersisted()) {
            markForBootSession(jobStatus);
        }
        maybeWriteStatusToDiskAsync();
        return replaced;
    }

    /**
     * Whether this jobStatus object already exists in the JobStore.
     */
    public boolean containsJobId(int jobId) {
        for (int i = mJobSet.size() - 1; i >= 0; i--) {
            JobStatus ts = mJobSet.valueAt(i);
            if (ts.matches(jobId)) {
                return true;
            }
        }
        return false;
    }

    boolean containsJob(JobStatus jobStatus) {
        return mJobSet.contains(jobStatus);
    }

    public int size() {
        return mJobSet.size();
    }

    /**
     * Remove the provided job. Will also delete the job if it was persisted.
     *
     * @return Whether or not the job existed to be removed.
     */
    public boolean remove(JobStatus jobStatus) {
        boolean removed = mJobSet.remove(jobStatus);
        if (!removed) {
            return false;
        }
        if (!jobStatus.isPersisted()) {
            unmarkForBootSession(jobStatus);
        }
        maybeWriteStatusToDiskAsync();
        return removed;
    }

    public void clear() {
        for (int i = 0; i < mJobSet.size(); i++) {
            JobStatus jobStatus = mJobSet.valueAt(i);
            if (!jobStatus.isPersisted()) {
                unmarkForBootSession(jobStatus);
            }
        }
        mJobSet.clear();
        maybeWriteStatusToDiskAsync();
    }

    /**
     * @param jobId Job id, specified at schedule-time.
     * @return the JobStatus that matches the provided uId and jobId, or null if none found.
     */
    public JobStatus getJobByJobId(int jobId) {
        Iterator<JobStatus> it = mJobSet.iterator();
        while (it.hasNext()) {
            JobStatus ts = it.next();
            if (ts.matches(jobId)) {
                return ts;
            }
        }
        return null;
    }

    /**
     * @return The live array of JobStatus objects.
     */
    public ArraySet<JobStatus> getJobs() {
        return mJobSet;
    }

    /**
     * Version of the db schema.
     */
    private static final int JOBS_FILE_VERSION = 0;
    /**
     * Tag corresponds to constraints this job needs.
     */
    private static final String XML_TAG_PARAMS_CONSTRAINTS = "constraints";
    /**
     * Tag corresponds to execution parameters.
     */
    private static final String XML_TAG_PERIODIC = "periodic";
    private static final String XML_TAG_ONEOFF = "one-off";
    private static final String XML_TAG_EXTRAS = "extras";

    /**
     * Every time the state changes we write all the jobs in one swath, instead of trying to
     * track incremental changes.
     *
     * @return Whether the operation was successful. This will only fail for e.g. if the system is
     * low on storage. If this happens, we continue as normal
     */
    private void maybeWriteStatusToDiskAsync() {
        mDirtyOperations++;
        if (mDirtyOperations >= MAX_OPS_BEFORE_WRITE) {
            mIoHandler.post(new WriteJobsMapToDiskRunnable());
        }
    }

    private void readJobMapFromDisk(ArraySet<JobStatus> jobSet) {
        new ReadJobMapFromDiskRunnable(jobSet).run();
    }

    /**
     * Runnable that writes {@link #mJobSet} out to xml.
     * NOTE: This Runnable locks on JobStore.this
     */
    private class WriteJobsMapToDiskRunnable implements Runnable {
        @Override
        public void run() {
            final long startElapsed = SystemClock.elapsedRealtime();
            List<JobStatus> mStoreCopy = new ArrayList<JobStatus>();
            synchronized (JobStore.this) {
                // Copy over the jobs so we can release the lock before writing.
                for (int i = 0; i < mJobSet.size(); i++) {
                    JobStatus jobStatus = mJobSet.valueAt(i);
                    JobStatus copy = new JobStatus(jobStatus.getJob(),
                            jobStatus.getEarliestRunTime(), jobStatus.getLatestRunTimeElapsed());
                    mStoreCopy.add(copy);
                }
            }
            writeJobsMapImpl(mStoreCopy);
        }

        private void writeJobsMapImpl(List<JobStatus> jobList) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(baos, "utf-8");
                out.startDocument(null, true);
                out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

                out.startTag(null, "job-info");
                out.attribute(null, "version", Integer.toString(JOBS_FILE_VERSION));
                for (int i = 0; i < jobList.size(); i++) {
                    JobStatus jobStatus = jobList.get(i);
                    out.startTag(null, "job");
                    addIdentifierAttributesToJobTag(out, jobStatus);
                    writeConstraintsToXml(out, jobStatus);
                    writeExecutionCriteriaToXml(out, jobStatus);
                    writeBundleToXml(jobStatus.getExtras(), out);
                    out.endTag(null, "job");
                }
                out.endTag(null, "job-info");
                out.endDocument();

                // Write out to disk in one fell sweep.
                FileOutputStream fos = mJobsFile.startWrite();
                fos.write(baos.toByteArray());
                mJobsFile.finishWrite(fos);
                mDirtyOperations = 0;
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            } catch (XmlPullParserException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }

        /**
         * Write out a tag with data comprising the required fields of this job and its client.
         */
        private void addIdentifierAttributesToJobTag(XmlSerializer out, JobStatus jobStatus)
                throws IOException {
            out.attribute(null, "jobid", Integer.toString(jobStatus.getJobId()));
            out.attribute(null, "package", jobStatus.getServiceComponent().getPackageName());
            out.attribute(null, "class", jobStatus.getServiceComponent().getClassName());
        }

        private void writeBundleToXml(PersistableBundle extras, XmlSerializer out)
                throws IOException, XmlPullParserException {
            out.startTag(null, XML_TAG_EXTRAS);
            extras.saveToXml(out);
            out.endTag(null, XML_TAG_EXTRAS);
        }

        /**
         * Write out a tag with data identifying this job's constraints. If the constraint isn't here
         * it doesn't apply.
         */
        private void writeConstraintsToXml(XmlSerializer out, JobStatus jobStatus) throws IOException {
            out.startTag(null, XML_TAG_PARAMS_CONSTRAINTS);
            if (jobStatus.hasUnmeteredConstraint()) {
                out.attribute(null, "unmetered", Boolean.toString(true));
            }
            if (jobStatus.hasConnectivityConstraint()) {
                out.attribute(null, "connectivity", Boolean.toString(true));
            }
            if (jobStatus.hasIdleConstraint()) {
                out.attribute(null, "idle", Boolean.toString(true));
            }
            if (jobStatus.hasChargingConstraint()) {
                out.attribute(null, "charging", Boolean.toString(true));
            }
            out.endTag(null, XML_TAG_PARAMS_CONSTRAINTS);
        }

        private void writeExecutionCriteriaToXml(XmlSerializer out, JobStatus jobStatus)
                throws IOException {
            final JobInfo job = jobStatus.getJob();
            if (jobStatus.getJob().isPeriodic()) {
                out.startTag(null, XML_TAG_PERIODIC);
                out.attribute(null, "period", Long.toString(job.getIntervalMillis()));
            } else {
                out.startTag(null, XML_TAG_ONEOFF);
            }

            if (jobStatus.isPersisted()) {
                out.attribute(null, "persisted", Boolean.toString(true));
            }

            if (jobStatus.hasDeadlineConstraint()) {
                // Wall clock deadline.
                final long deadlineWallclock = System.currentTimeMillis() +
                        (jobStatus.getLatestRunTimeElapsed() - SystemClock.elapsedRealtime());
                out.attribute(null, "deadline", Long.toString(deadlineWallclock));
            }
            if (jobStatus.hasTimingDelayConstraint()) {
                final long delayWallclock = System.currentTimeMillis() +
                        (jobStatus.getEarliestRunTime() - SystemClock.elapsedRealtime());
                out.attribute(null, "delay", Long.toString(delayWallclock));
            }

            // Only write out back-off policy if it differs from the default.
            // This also helps the case where the job is idle -> these aren't allowed to specify
            // back-off.
            if (jobStatus.getJob().getInitialBackoffMillis() != JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS
                    || jobStatus.getJob().getBackoffPolicy() != JobInfo.DEFAULT_BACKOFF_POLICY) {
                out.attribute(null, "backoff-policy", Integer.toString(job.getBackoffPolicy()));
                out.attribute(null, "initial-backoff", Long.toString(job.getInitialBackoffMillis()));
            }
            if (job.isPeriodic()) {
                out.endTag(null, XML_TAG_PERIODIC);
            } else {
                out.endTag(null, XML_TAG_ONEOFF);
            }
        }
    }

    /**
     * Runnable that reads list of persisted job from xml. This is run once at start up, so doesn't
     * need to go through {@link JobStore#add(JobStatus)}.
     */
    private class ReadJobMapFromDiskRunnable implements Runnable {
        private final ArraySet<JobStatus> jobSet;

        /**
         * @param jobSet Reference to the (empty) set of JobStatus objects that back the JobStore,
         *               so that after disk read we can populate it directly.
         */
        ReadJobMapFromDiskRunnable(ArraySet<JobStatus> jobSet) {
            this.jobSet = jobSet;
        }

        @Override
        public void run() {
            try {
                List<JobStatus> jobs;
                FileInputStream fis = mJobsFile.openRead();
                synchronized (JobStore.this) {
                    jobs = readJobMapImpl(fis);
                    if (jobs != null) {
                        for (int i = 0; i < jobs.size(); i++) {
                            JobStatus jobStatus = jobs.get(i);
                            // Skip any jobs that are not persisted if we have rebooted.
                            if (!jobStatus.isPersisted() && !isMarkedForBootSession(jobStatus)) {
                                continue;
                            }
                            this.jobSet.add(jobStatus);
                        }
                    }
                }
                fis.close();
            } catch (FileNotFoundException e) {
                // Ignore
            } catch (XmlPullParserException e) {
                // Ignore
            } catch (IOException e) {
                // Ignore
            }
        }

        private List<JobStatus> readJobMapImpl(FileInputStream fis)
                throws XmlPullParserException, IOException {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            if (eventType == XmlPullParser.END_DOCUMENT) {
                return null;
            }

            String tagName = parser.getName();
            if ("job-info".equals(tagName)) {
                final List<JobStatus> jobs = new ArrayList<JobStatus>();
                // Read in version info.
                try {
                    int version = Integer.valueOf(parser.getAttributeValue(null, "version"));
                    if (version != JOBS_FILE_VERSION) {
                        return null;
                    }
                } catch (NumberFormatException e) {
                    return null;
                }
                eventType = parser.next();
                do {
                    // Read each <job/>
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        // Start reading job.
                        if ("job".equals(tagName)) {
                            JobStatus persistedJob = restoreJobFromXml(parser);
                            if (persistedJob != null) {
                                jobs.add(persistedJob);
                            }
                        }
                    }
                    eventType = parser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);
                return jobs;
            }
            return null;
        }

        /**
         * @param parser Xml parser at the beginning of a "<job/>" tag. The next "parser.next()" call
         *               will take the parser into the body of the job tag.
         * @return Newly instantiated job holding all the information we just read out of the xml tag.
         */
        private JobStatus restoreJobFromXml(XmlPullParser parser) throws XmlPullParserException,
                IOException {
            JobInfo.Builder jobBuilder;

            // Read out job identifier attributes.
            try {
                jobBuilder = buildBuilderFromXml(parser);
            } catch (NumberFormatException e) {
                return null;
            }

            int eventType;
            // Read out constraints tag.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);  // Push through to next START_TAG.

            if (!(eventType == XmlPullParser.START_TAG &&
                    XML_TAG_PARAMS_CONSTRAINTS.equals(parser.getName()))) {
                // Expecting a <constraints> start tag.
                return null;
            }
            try {
                buildConstraintsFromXml(jobBuilder, parser);
            } catch (NumberFormatException e) {
                return null;
            }
            parser.next(); // Consume </constraints>

            // Read out execution parameters tag.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);
            if (eventType != XmlPullParser.START_TAG) {
                return null;
            }

            Pair<Long, Long> runtimes;
            try {
                runtimes = buildExecutionTimesFromXml(parser);
            } catch (NumberFormatException e) {
                return null;
            }

            if (XML_TAG_PERIODIC.equals(parser.getName())) {
                try {
                    String val = parser.getAttributeValue(null, "period");
                    jobBuilder.setPeriodic(Long.valueOf(val));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (XML_TAG_ONEOFF.equals(parser.getName())) {
                try {
                    if (runtimes.first != JobStatus.NO_EARLIEST_RUNTIME) {
                        jobBuilder.setMinimumLatency(runtimes.first - SystemClock.elapsedRealtime());
                    }
                    if (runtimes.second != JobStatus.NO_LATEST_RUNTIME) {
                        jobBuilder.setOverrideDeadline(
                                runtimes.second - SystemClock.elapsedRealtime());
                    }
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                // Expecting a parameters start tag.
                return null;
            }

            String persisted = parser.getAttributeValue(null, "persisted");
            if (persisted != null) {
                jobBuilder.setPersisted(Boolean.valueOf(persisted));
            }

            maybeBuildBackoffPolicyFromXml(jobBuilder, parser);

            parser.nextTag(); // Consume parameters end tag.

            // Read out extras Bundle.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);
            if (!(eventType == XmlPullParser.START_TAG && XML_TAG_EXTRAS.equals(parser.getName()))) {
                return null;
            }

            PersistableBundle extras = PersistableBundle.restoreFromXml(parser);
            jobBuilder.setExtras(extras);
            parser.nextTag(); // Consume </extras>

            return new JobStatus(jobBuilder.build(), runtimes.first, runtimes.second);
        }

        private JobInfo.Builder buildBuilderFromXml(XmlPullParser parser) throws NumberFormatException {
            // Pull out required fields from <job> attributes.
            int jobId = Integer.valueOf(parser.getAttributeValue(null, "jobid"));
            String packageName = parser.getAttributeValue(null, "package");
            String className = parser.getAttributeValue(null, "class");
            ComponentName cname = new ComponentName(packageName, className);

            return new JobInfo.Builder(jobId, cname);
        }

        private void buildConstraintsFromXml(JobInfo.Builder jobBuilder, XmlPullParser parser) {
            String val = parser.getAttributeValue(null, "unmetered");
            if (val != null) {
                jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
            }
            val = parser.getAttributeValue(null, "connectivity");
            if (val != null) {
                jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            }
            val = parser.getAttributeValue(null, "idle");
            if (val != null) {
                jobBuilder.setRequiresDeviceIdle(true);
            }
            val = parser.getAttributeValue(null, "charging");
            if (val != null) {
                jobBuilder.setRequiresCharging(true);
            }
        }

        /**
         * Builds the back-off policy out of the params tag. These attributes may not exist, depending
         * on whether the back-off was set when the job was first scheduled.
         */
        private void maybeBuildBackoffPolicyFromXml(JobInfo.Builder jobBuilder, XmlPullParser parser) {
            String val = parser.getAttributeValue(null, "initial-backoff");
            if (val != null) {
                long initialBackoff = Long.valueOf(val);
                val = parser.getAttributeValue(null, "backoff-policy");
                int backoffPolicy = Integer.valueOf(val);  // Will throw NFE which we catch higher up.
                jobBuilder.setBackoffCriteria(initialBackoff, backoffPolicy);
            }
        }

        /**
         * Convenience function to read out and convert deadline and delay from xml into elapsed real
         * time.
         *
         * @return A {@link android.util.Pair}, where the first value is the earliest elapsed runtime
         * and the second is the latest elapsed runtime.
         */
        private Pair<Long, Long> buildExecutionTimesFromXml(XmlPullParser parser)
                throws NumberFormatException {
            // Pull out execution time data.
            final long nowWallclock = System.currentTimeMillis();
            final long nowElapsed = SystemClock.elapsedRealtime();

            long earliestRunTimeElapsed = JobStatus.NO_EARLIEST_RUNTIME;
            long latestRunTimeElapsed = JobStatus.NO_LATEST_RUNTIME;
            String val = parser.getAttributeValue(null, "deadline");
            if (val != null) {
                long latestRuntimeWallclock = Long.valueOf(val);
                long maxDelayElapsed =
                        Math.max(latestRuntimeWallclock - nowWallclock, 0);
                latestRunTimeElapsed = nowElapsed + maxDelayElapsed;
            }
            val = parser.getAttributeValue(null, "delay");
            if (val != null) {
                long earliestRuntimeWallclock = Long.valueOf(val);
                long minDelayElapsed =
                        Math.max(earliestRuntimeWallclock - nowWallclock, 0);
                earliestRunTimeElapsed = nowElapsed + minDelayElapsed;

            }
            return Pair.create(earliestRunTimeElapsed, latestRunTimeElapsed);
        }
    }

    private void markForBootSession(JobStatus jobStatus) {
        // Pending intents are cleared on reboot. Therefore, we can use one to mark that we haven't
        // rebooted yet.
        Intent intent = new Intent(mContext, JobSchedulerService.class)
                .setAction(jobStatus.toShortString());
        PendingIntent.getService(mContext, jobStatus.getJobId(), intent, PendingIntent.FLAG_CANCEL_CURRENT);
    }

    private void unmarkForBootSession(JobStatus jobStatus) {
        Intent intent = new Intent(mContext, JobSchedulerService.class)
                .setAction(jobStatus.toShortString());
        PendingIntent pendingIntent = PendingIntent.getService(mContext, jobStatus.getJobId(), intent, PendingIntent.FLAG_NO_CREATE);
        if (pendingIntent != null) {
            pendingIntent.cancel();
        }
    }

    private boolean isMarkedForBootSession(JobStatus jobStatus) {
        Intent intent = new Intent(mContext, JobSchedulerService.class)
                .setAction(jobStatus.toShortString());
        PendingIntent pendingIntent = PendingIntent.getService(mContext, jobStatus.getJobId(), intent, PendingIntent.FLAG_NO_CREATE);
        return pendingIntent != null;
    }
}
