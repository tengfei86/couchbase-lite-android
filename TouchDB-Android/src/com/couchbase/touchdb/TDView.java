/**
 * Original iOS version by  Jens Alfke
 * Ported to Android by Marty Schoch
 *
 * Copyright (c) 2012 Couchbase, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.couchbase.touchdb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jackson.map.ObjectMapper;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

public class TDView {

    private TDDatabase db;
    private String name;
    private int viewId;
    private TDViewMapBlock mapBlock;

    public TDView(TDDatabase db, String name) {
        this.db = db;
        this.name = name;
        this.viewId = -1;  // means 'unknown'
    }

    public TDDatabase getDb() {
        return db;
    };

    public String getName() {
        return name;
    }

    public TDViewMapBlock getMapBlock() {
        return mapBlock;
    }

    public int getViewId() {
        if(viewId < 0) {
            String sql = "SELECT view_id FROM views WHERE name=?";
            String[] args = {name};
            Cursor cursor = null;
            try {
                cursor = db.getDatabase().rawQuery(sql, args);
                if(cursor.moveToFirst()) {
                    viewId = cursor.getInt(0);
                }
                else {
                    viewId = 0;
                }
            } catch (SQLException e) {
                Log.e(TDDatabase.TAG, "Error getting view id", e);
                viewId = 0;
            } finally {
                if(cursor != null) {
                    cursor.close();
                }
            }
        }
        return viewId;
    }

    public long getLastSequenceIndexed() {
        String sql = "SELECT lastSequence FROM views WHERE name=?";
        String[] args = {name};
        Cursor cursor = null;
        long result = -1;
        try {
            cursor = db.getDatabase().rawQuery(sql, args);
            if(cursor.moveToFirst()) {
                result = cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.e(TDDatabase.TAG, "Error getting last sequence indexed");
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    public boolean setMapBlock(TDViewMapBlock mapBlock, String version) {
        assert(mapBlock != null);
        assert(version != null);

        this.mapBlock = mapBlock;

        // Update the version column in the db. This is a little weird looking because we want to
        // avoid modifying the db if the version didn't change, and because the row might not exist yet.
        SQLiteDatabase database = db.getDatabase();

        // Older Android doesnt have reliable insert or ignore, will to 2 step
        // FIXME review need for change to execSQL, manual call to changes()

        String sql = "SELECT name, version FROM views WHERE name=?";
        String[] args = {name};
        Cursor cursor = null;

        try {
            cursor = db.getDatabase().rawQuery(sql, args);
            if(!cursor.moveToFirst()) {
                // no such record, so insert
                ContentValues insertValues = new ContentValues();
                insertValues.put("name", name);
                insertValues.put("version", version);
                database.insert("views", null, insertValues);
                return true;
            }

            ContentValues updateValues = new ContentValues();
            updateValues.put("version", version);
            updateValues.put("lastSequence", 0);

            String[] whereArgs = { name, version };
            int rowsAffected = database.update("views", updateValues, "name=? AND version!=?", whereArgs);

            return (rowsAffected > 0);
        } catch (SQLException e) {
            Log.e(TDDatabase.TAG, "Error setting map block", e);
            return false;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

    }

    public void removeIndex() {
        if(viewId < 0) {
            return;
        }

        try {
            db.beginTransaction();

            String[] whereArgs = { Integer.toString(viewId) };
            db.getDatabase().delete("maps", "view_id=?", whereArgs);

            ContentValues updateValues = new ContentValues();
            updateValues.put("lastSequence", 0);
            db.getDatabase().update("views", updateValues, "view_id=?", whereArgs);

            db.endTransaction();
        } catch (SQLException e) {
            Log.e(TDDatabase.TAG, "Error removing index", e);
        }
    }

    public void deleteView() {
        db.deleteViewNamed(name);
        viewId = 0;
    }

    /*** Indexing ***/

    public static String toJSONString(Object object) {
        if(object == null) {
            return null;
        }
        ObjectMapper mapper = new ObjectMapper();
        String result = null;
        try {
            result = mapper.writeValueAsString(object);
        } catch(Exception e) {
            //ignore
        }
        return result;
    }

    public static Object fromJSON(byte[] json) {
        if(json == null) {
            return null;
        }
        ObjectMapper mapper = new ObjectMapper();
        Object result = null;
        try {
            result = mapper.readValue(json, Object.class);
        } catch (Exception e) {
            //ignore
        }
        return result;
    }

    //FIXME review this method may need better exception handling within transaction
    @SuppressWarnings("unchecked")
    public TDStatus updateIndex() {
        Log.v(TDDatabase.TAG, "Re-indexing view " + name + " ...");
        assert(mapBlock != null);

        if(viewId < 0) {
            return new TDStatus(TDStatus.NOT_FOUND);
        }

        db.beginTransaction();
        TDStatus result = new TDStatus(TDStatus.INTERNAL_SERVER_ERROR);

        long sequence = 0;

        long lastSequence = getLastSequenceIndexed();
        if(lastSequence < 0) {
            Log.e(TDDatabase.TAG, "Failed to rebuild view");
            db.getDatabase().endTransaction();
            return result;
        }
        sequence = lastSequence;

        // This is the emit() block, which gets called from within the user-defined map() block
        // that's called down below.
        AbstractTouchMapEmitBlock emitBlock = new AbstractTouchMapEmitBlock() {

            @Override
            public void emit(Object key, Object value) {
                if(key == null) {
                    return;
                }
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    String keyJson = mapper.writeValueAsString(key);
                    String valueJson = mapper.writeValueAsString(value);
                    Log.v(TDDatabase.TAG, "    emit(" + keyJson + ", " + valueJson + ")");

                    ContentValues insertValues = new ContentValues();
                    insertValues.put("view_id", viewId);
                    insertValues.put("sequence", sequence);
                    insertValues.put("key", keyJson);
                    insertValues.put("value", valueJson);
                    db.getDatabase().insert("maps", null, insertValues);
                } catch (Exception e) {
                    Log.e(TDDatabase.TAG, "Error emitting", e);
                    //find a better way to propogate this back
                }
            }
        };

        // If the lastSequence has been reset to 0, make sure to remove any leftover rows:
        if(lastSequence == 0) {
            String[] whereArgs = { Integer.toString(viewId) };
            db.getDatabase().delete("maps", "view_id=?", whereArgs);
        }

        // Now scan every revision added since the last time the view was indexed:
        String[] selectArgs = { Long.toString(lastSequence), Long.toString(lastSequence) };
        Cursor cursor = null;


        try {
            cursor = db.getDatabase().rawQuery("SELECT sequence, parent, current, deleted, json FROM revs WHERE sequence>? AND ((parent>0 AND parent<?) OR (current!=0 AND deleted=0))", selectArgs);
            cursor.moveToFirst();

            while(!cursor.isAfterLast()) {
                sequence = cursor.getLong(0);
                long parentSequence = cursor.getLong(1);
                boolean current = (cursor.getInt(2) > 0);
                boolean deleted = (cursor.getInt(3) > 0);
                byte[] json = cursor.getBlob(4);

                Log.v(TDDatabase.TAG, "Seq# " + Long.toString(sequence));

                if((parentSequence != 0) && (parentSequence <= lastSequence)) {
                    // Delete any map results emitted from now-obsolete revisions:
                    Log.v(TDDatabase.TAG, "  delete maps for sequence=" + Long.toString(parentSequence));
                    String[] whereArgs = { Long.toString(parentSequence), Integer.toString(viewId) };
                    db.getDatabase().delete("maps", "sequence=? AND view_id=?", whereArgs);
                }

                if(current && !deleted) {
                    // Call the user-defined map() to emit new key/value pairs from this revision:
                    Log.v(TDDatabase.TAG, "  call map for sequence=" + Long.toString(sequence));
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> properties = null;
                    try {
                        emitBlock.setSequence(sequence);
                        properties = mapper.readValue(json, Map.class);
                    } catch (Exception e) {
                        //ignore
                    }

                    if(properties != null) {
                        mapBlock.map(properties, emitBlock);
                    }
                }

                cursor.moveToNext();
            }

            // Finally, record the last revision sequence number that was indexed:
            ContentValues updateValues = new ContentValues();
            updateValues.put("lastSequence", sequence);

            String[] whereArgs = { Integer.toString(viewId) };
            db.getDatabase().update("views", updateValues, "view_id=?", whereArgs);
        } catch (SQLException e) {
            Log.e(TDDatabase.TAG, "Error re-indexing view", e);
            db.endTransaction();
            return result;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        Log.v(TDDatabase.TAG, "...Finished re-indexing view " + name + " up to sequence " + Long.toString(sequence));
        result.setCode(TDStatus.OK);
        db.endTransaction();
        return result;
    }

    /*** Querying ***/
    public List<Map<String,Object>> dump() {
        if(viewId < 0) {
            return null;
        }

        String[] selectArgs = { Integer.toString(viewId) };
        Cursor cursor = null;
        List<Map<String, Object>> result = null;

        try {
            cursor = db.getDatabase().rawQuery("SELECT sequence, key, value FROM maps WHERE view_id=? ORDER BY key", selectArgs);

            cursor.moveToFirst();
            result = new ArrayList<Map<String,Object>>();
            while(!cursor.isAfterLast()) {
                Map<String,Object> row = new HashMap<String,Object>();
                row.put("seq", cursor.getInt(0));
                row.put("key", cursor.getString(1));
                row.put("value", cursor.getString(2));
                result.add(row);
                cursor.moveToNext();
            }
        } catch (SQLException e) {
            Log.e(TDDatabase.TAG, "Error dumping view", e);
            return null;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> queryWithOptions(TDQueryOptions options) {
        if(options == null) {
            options = new TDQueryOptions();
        }

        TDStatus updateStatus = updateIndex();
        if(!updateStatus.isSuccessful()) {
            return null;
        }

        long update_seq = 0;
        if(options.isUpdateSeq()) {
            update_seq = getLastSequenceIndexed();
        }

        String sql = "SELECT key, value, docid";
        if(options.isIncludeDocs()) {
            sql = sql + ", revid, json, revs.sequence";
        }
        sql = sql + " FROM maps, revs, docs ";
        sql = sql + "WHERE maps.view_id=? AND revs.sequence = maps.sequence AND docs.doc_id = revs.doc_id ORDER BY key";
        if(options.isDescending()) {
            sql = sql + " DESC";
        }
        sql = sql + " LIMIT ? OFFSET ?";
        String[] args = { Integer.toString(viewId), Integer.toString(options.getLimit()), Integer.toString(options.getSkip()) };

        Cursor cursor = null;

        List<Map<String, Object>> rows;
        try {
            cursor = db.getDatabase().rawQuery(sql, args);

            cursor.moveToFirst();
            rows = new ArrayList<Map<String,Object>>();
            while(!cursor.isAfterLast()) {
                Map<String,Object> row = new HashMap<String,Object>();
                Object key = fromJSON(cursor.getBlob(0));
                Object value = fromJSON(cursor.getBlob(1));
                String docId = cursor.getString(2);
                Map<String,Object> docContents = null;
                if(options.isIncludeDocs()) {
                    String revId = cursor.getString(3);
                    byte[] docBytes = cursor.getBlob(4);
                    long sequence = cursor.getLong(5);
                    ObjectMapper mapper = new ObjectMapper();
                    try {
                        docContents = mapper.readValue(docBytes, Map.class);
                        docContents.put("_id", docId);
                        docContents.put("_rev", revId);
                        docContents.put("_attachments", db.getAttachmentsDictForSequenceWithContent(sequence, false));
                    } catch (Exception e) {
                        Log.w(TDDatabase.TAG, "Unable to parse document JSON in view");
                    }

                }
                row.put("id", docId);
                row.put("key", key);
                row.put("value", value);
                row.put("doc", docContents);
                rows.add(row);
                cursor.moveToNext();
            }

        } catch (SQLException e) {
            Log.e(TDDatabase.TAG, "Error querying view", e);
            return null;
        } finally {
            if(cursor != null) {
                cursor.close();
            }
        }

        int totalRows = rows.size();  //??? Is this true, or does it ignore limit/offset?
        Map<String,Object> result = new HashMap<String,Object>();
        result.put("rows", rows);
        result.put("total_rows", totalRows);
        result.put("offset", options.getSkip());
        result.put("update_seq", ((update_seq != 0) ? update_seq : null));

        return result;
    }

}

abstract class AbstractTouchMapEmitBlock implements TDViewMapEmitBlock {

    protected long sequence = 0;

    void setSequence(long sequence) {
        this.sequence = sequence;
    }

}
