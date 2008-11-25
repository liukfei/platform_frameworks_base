/*
 * Copyright (C) 2006 The Android Open Source Project
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
 * limitations under the License.
 */


package com.android.internal.telephony.cdma;

import android.content.pm.PackageManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import com.android.internal.telephony.IccPhoneBookInterfaceManager;
import com.android.internal.telephony.AdnRecordCache; 
import com.android.internal.telephony.AdnRecord;

/**
 * RuimPhoneBookInterfaceManager to provide an inter-process communication to
 * access ADN-like SIM records.
 */

//
//TODO Check if a common IccPhoneBookInterfaceManager is enough!!!!
//
public class RuimPhoneBookInterfaceManager extends IccPhoneBookInterfaceManager {
    static final String LOG_TAG = "CDMA";
    static final boolean DBG = false;

    private CDMAPhone phone;
    private AdnRecordCache adnCache;
    private final Object mLock = new Object();
    private int recordSize[];
    private boolean success;
    private List<AdnRecord> records;

    private static final boolean ALLOW_SIM_OP_IN_UI_THREAD = false;

    private static final int EVENT_GET_SIZE_DONE = 1;
    private static final int EVENT_LOAD_DONE = 2;
    private static final int EVENT_UPDATE_DONE = 3;

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case EVENT_GET_SIZE_DONE:
                    Log.d(LOG_TAG, "Event EVENT_GET_SIZE_DONE Received"); //TODO
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            recordSize = (int[])ar.result;
                            // recordSize[0]  is the record length
                            // recordSize[1]  is the total length of the EF file
                            // recordSize[2]  is the number of records in the EF file
                            log("GET_RECORD_SIZE Size " + recordSize[0] +
                                    " total " + recordSize[1] +
                                    " #record " + recordSize[2]);
                            mLock.notifyAll();
                        }
                    }
                    break;
                case EVENT_UPDATE_DONE:
                    Log.d(LOG_TAG, "Event EVENT_UPDATE_DONE Received"); //TODO
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        success = (ar.exception == null);
                        mLock.notifyAll();
                    }
                    break;
                case EVENT_LOAD_DONE:
                    Log.d(LOG_TAG, "Event EVENT_LOAD_DONE Received"); //TODO
                    ar = (AsyncResult)msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            records = (List<AdnRecord>)
                                    ((ArrayList<AdnRecord>) ar.result);
                        } else {
                            if(DBG) log("Cannot load ADN records");
                            if (records != null) {
                                records.clear();
                            }
                        }
                        mLock.notifyAll();
                    }
                    break;
            }
        }
    };

    public RuimPhoneBookInterfaceManager(CDMAPhone phone) {
        this.phone = phone;
        adnCache = phone.mRuimRecords.getAdnCache(); 
        //publish(); //TODO REMOVE        
    }

    private void publish() {
        // TODO T: Do we have to change the service 
        //         as well to "iccphonebook"?
        //         defined in: device/commands/binder/Service_info.c        
        ServiceManager.addService("simphonebook", this);
    }

    /**
     * Replace oldAdn with newAdn in ADN-like record in EF
     *
     * getAdnRecordsInEf must be called at least once before this function,
     * otherwise an error will be returned
     * throws SecurityException if no WRITE_CONTACTS permission
     *
     * @param efid must be one among EF_ADN, EF_FDN, and EF_SDN
     * @param oldTag adn tag to be replaced
     * @param oldPhoneNumber adn number to be replaced
     *        Set both oldTag and oldPhoneNubmer to "" means to replace an
     *        empty record, aka, insert new record
     * @param newTag adn tag to be stored
     * @param newPhoneNumber adn number ot be stored
     *        Set both newTag and newPhoneNubmer to "" means to replace the old
     *        record with empty one, aka, delete old record
     * @param pin2 required to update EF_FDN, otherwise must be null
     * @return true for success
     */
    public boolean
    updateAdnRecordsInEfBySearch (int efid,
            String oldTag, String oldPhoneNumber,
            String newTag, String newPhoneNumber, String pin2) {
        success = false;
        return success;
    }

    /**
     * Update an ADN-like EF record by record index
     *
     * This is useful for iteration the whole ADN file, such as write the whole
     * phone book or erase/format the whole phonebook
     * throws SecurityException if no WRITE_CONTACTS permission
     *
     * @param efid must be one among EF_ADN, EF_FDN, and EF_SDN
     * @param newTag adn tag to be stored
     * @param newPhoneNumber adn number to be stored
     *        Set both newTag and newPhoneNubmer to "" means to replace the old
     *        record with empty one, aka, delete old record
     * @param index is 1-based adn record index to be updated
     * @param pin2 required to update EF_FDN, otherwise must be null
     * @return true for success
     */
    public boolean
    updateAdnRecordsInEfByIndex(int efid, String newTag,
            String newPhoneNumber, int index, String pin2) {
        if (phone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }

        if (DBG) log("updateAdnRecordsInEfByIndex: efid=" + efid +
                " Index=" + index + " ==> " +
                "("+ newTag + "," + newPhoneNumber + ")"+ " pin2=" + pin2);
        synchronized(mLock) {
            checkThread();
            success = false;
            Message response = mHandler.obtainMessage(EVENT_UPDATE_DONE);
            AdnRecord newAdn = new AdnRecord(newTag, newPhoneNumber);
            adnCache.updateAdnByIndex(efid, newAdn, index, pin2, response);
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to update by index");
            }
        }
        return success;
    }

    /**
     * Get the capacity of records in efid
     *
     * @param efid the EF id of a ADN-like SIM
     * @return  int[3] array
     *            recordSizes[0]  is the single record length
     *            recordSizes[1]  is the total length of the EF file
     *            recordSizes[2]  is the number of records in the EF file
     */
    public int[] getAdnRecordsSize(int efid) {
        if (DBG) log("getAdnRecordsSize: efid=" + efid);
        synchronized(mLock) {
            checkThread();
            recordSize = new int[3];
            Message response = mHandler.obtainMessage(EVENT_GET_SIZE_DONE);
            ((RuimFileHandler)phone.getIccFileHandler()).getEFLinearRecordSize(efid, response);
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to load from the SIM");
            }
        }

        return recordSize;
    }

    /**
     * Loads the AdnRecords in efid and returns them as a
     * List of AdnRecords
     *
     * throws SecurityException if no READ_CONTACTS permission
     *
     * @param efid the EF id of a ADN-like SIM
     * @return List of AdnRecord
     */
    public List<AdnRecord> getAdnRecordsInEf(int efid) {
        if (phone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.READ_CONTACTS permission");
        }

        if (DBG) log("getAdnRecordsInEF: efid=" + efid);

        synchronized(mLock) {
            checkThread();
            Message response = mHandler.obtainMessage(EVENT_LOAD_DONE);
            adnCache.requestLoadAllAdnLike(efid, response);
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to load from the SIM");
            }
        }
        return records;
    }

    private void checkThread() {
        if (!ALLOW_SIM_OP_IN_UI_THREAD) {
            // Make sure this isn't the UI thread, since it will block
            if (mHandler.getLooper().equals(Looper.myLooper())) {
                Log.e(LOG_TAG, "query() called on the main UI thread!");
                throw new IllegalStateException(
                        "You cannot call query on this provder from the main UI thread.");
            }
        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[RuimPbInterfaceManager] " + msg);
    }
}

