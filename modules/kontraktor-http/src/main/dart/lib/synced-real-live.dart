import 'dart:convert';
import 'dart:io';
import 'kontraktor-client.dart';
import 'real-live.dart';

abstract class LocalPersistance {
  // return map key => record
  Future<Map<String,dynamic>> loadRecords(String tableName);
  Future<Map<String,dynamic>> loadProps(String tableName);
  // save all or just modified ones
  void persistRecords(String tableName, Map recs, [Set<String> changedRecordKeys = null]);
  void persistProps(String tableName, Map syncState);
}

class FileTablePersistance extends LocalPersistance {

  Directory base;

  FileTablePersistance(String baseDir) {
    base = Directory(baseDir);
    base.createSync(recursive: true);
  }

  @override
  Future<Map<String,dynamic>> loadProps(String tableName) async {
    File target = File(base.path+"/${tableName}_props.json");
    String s;
    if ( ! target.existsSync() )
      s = "{}";
    else
      s = target.readAsStringSync();
    try {
      return jsonDecode(s);
    } catch (e,t) {
      print("$e $t");
      return {
        "serverTS": 0
      };
    }
  }

  @override
  Future<Map<String,dynamic>> loadRecords(String tableName) async {
    File target = File(base.path+"/${tableName}.json");
    String s;
    if ( ! target.existsSync() )
      s = "{}";
    else
      s = target.readAsStringSync();
    try {
      return jsonDecode(s);
    } catch (e,t) {
      print("$e $t");
      return {};
    }
  }

  @override
  void persistProps(String tableName, Map syncState) {
    File target = File(base.path+"/${tableName}_props.json");
    target.writeAsStringSync(jsonEncode(syncState));
  }

  @override
  void persistRecords(String tableName, Map recs, [Set<String> changedRecordKeys = null]) {
    File target = File(base.path+"/${tableName}.json");
    target.writeAsStringSync(jsonEncode(recs));
  }

}

class SyncedRealLive {

  static SyncedRealLive singleton = SyncedRealLive();

  Map<String,SyncedRLTable> syncedTableCache = Map();
  LocalPersistance persistance;
  RLJsonSession session;

  String serverUrl;
  String localDataDir;
  String usr, pwd;
  bool connectLoopRunning = false;
  Map rlProps = Map();

  SyncedRealLive init(String serverUrl,String localDataDir,String usr, String pwd) {
    this.serverUrl = serverUrl; this.localDataDir = localDataDir; this.usr = usr; this.pwd = pwd;
    persistance = FileTablePersistance(localDataDir);
    return this;
  }

  initTable(String alias, String name, query, [maxAgeMS = 0]) {
    syncedTableCache[alias] = new SyncedRLTable(this, name, query, maxAgeMS);
  }

  String getLocalId() {
    var rlProp = rlProps["localId"];
    if ( rlProp == null ) {
      rlProps["localId"] = uuid.v4();
      persistance.persistProps("_internal_", rlProps);
      rlProp = rlProps["localId"];
    }
    return rlProp;
  }

  startConnection() async {
    if ( connectLoopRunning )
      throw "connectloop already started";

    rlProps = await persistance.loadProps("_internal_");
    connectLoopRunning = true;

    List<Future> futs = List();
    syncedTableCache.forEach( (name,st) => futs.add(st.init()) );
    await Future.wait(futs);

    await _connect();
  }

  _connect() async {
    try {
      var sess = RLJsonSession(serverUrl);
      await sess.authenticate(usr, pwd);
      session = sess;
      _onSuccessfulConnection();
    } catch (e) {
      print("con: $e");
      Future.delayed(Duration(milliseconds: 3000), () => _connect());
    }

  }

  operator [](String tableName) {
    return syncedTableCache[tableName];
  }

  RLTable getRLTable( String name ) {
    if ( session != null ) {
      return RLTable(session,name);
    }
    return null;
  }

  void _onSuccessfulConnection() {
    syncedTableCache.forEach( (name,st) async {
      await st.syncToServer();
      st.syncFromServer();
    });
  }

}

class SyncedRLTable {

  SyncedRealLive source;
  String tableName;

  Map records;
  Map prefs;
  String query;
  String subsId;
  bool queryDone = false;
  Set<String> initialSynced = Set();
  int maxAgeMS;
  Map<String,dynamic> localTransactions;
  int lastUpload = 0;

  Function(String,Map,SyncedRLTable) listener;

  SyncedRLTable(this.source,this.tableName,this.query,[this.maxAgeMS = 0]);

  // loads persisted items from files
  Future init() async {
    prefs = await source.persistance.loadProps(tableName);
    if ( prefs["serverTS"] == null )
      prefs["serverTS"] = 0;
    if ( prefs["pending"] == null )
      prefs["pending"] = Map<String,dynamic>();
    localTransactions = prefs["pending"];
    records = await source.persistance.loadRecords(tableName);
    if ( maxAgeMS > 0 ) {
      var newRecs = Map();
      int serverTS = prefs["serverTS"];
      if ( serverTS > 0 ) {
        records.forEach((k, v) {
          if ( serverTS - v["lastModified"] < maxAgeMS ) {
            newRecs[k] = v;
          }
        });
        records = newRecs;
        persistState();
      }
    }
    _fireInitialLoad();
  }

  Map<String,dynamic> operator [](String key) {
    return records[key];
  }

  String createKey() {
    return uuid.v4();
  }

  void addOrUpdate( Map<String,dynamic> values ) async {
    String key = values["key"];
    var record = records[key];
    bool add = false;
    if ( record == null ) {
      records[key] = values;
      add = true;
    } else {
      values.forEach( (k,v) {
        record[k] = v;
      });
    }
    List<Map> ltrans = localTransactions[key];
    if ( ltrans == null ) {
      ltrans = List<Map>();
      localTransactions[key] = ltrans;
    }
    ltrans.add( values );
    persistState( ids: Set()..add(key) );
    //FIXME: persist un ack'ed transactions
    // bulkupload
    syncToServer();
    _fireLocalAddOrUpdate(add,records[key], values);
  }

  void delete( String key ) {
    var record = records[key];
    if ( record == null )
      return;
    addOrUpdate({ "key": key, "_del": true });
    records.remove(key);
    persistState( ids: Set()..add(key) );
    _fireLocalDelete(key);
  }

  /**
   * upload pending local transactions
   */
  Future<int> syncToServer() async {
    RLTable table = source.getRLTable(tableName);
    if ( table == null ) {
      return 0;
    }
    if ( localTransactions.length == 0 )
      return 0;
    // bulkupload
    var lts = localTransactions;
    localTransactions = Map();
    try {
      int i = await table.bulkUpdate(lts);
      if ( i > 0 ) {
        lastUpload = i;
        _fireLastUploadChanged(i);
      }
    } catch ( e ) {
      print("$e");
      // rollback
      lts.forEach( (k,v) {
        if ( localTransactions.containsKey(k) ) {
          v.addAll(localTransactions[k]);
          localTransactions[k] = v;
        } else {
          localTransactions[k] = v;
        }
      });
    }
    persistPrefs();
  }

  /**
   * future resolves on query finished, further broadcasts are promoted via listener
   */
  Future syncFromServer() async {
    RLTable table = source.getRLTable(tableName);
    if ( table == null ) {
      return;
    }
    var resarr = await table.subscribeSyncing(prefs["serverTS"], query, (res,err) {
      if ( res != null ) {
        print("tsync event $res");
        String type = res["type"];
        switch (type) {
          case "UPDATE":
          case "ADD": {
            // as query is time and feed based, "ADD" does not mean new record, but
            // also changed record.
            updateServerTS(res["record"]["lastModified"]);
            // ignore self sent
            if ( res["senderId"] == table.jsonsession.senderId) {
              print("suppress own add");
              return;
            }
            var newRecord = res["record"];
            var key = newRecord["key"];
            var oldRec = records[key];
            var deleted = res["record"]["_del"];
            if ( deleted != null ) {
              if ( oldRec != null ) {
                records.remove(key);
                if ( queryDone ) {
                  _fireDeletion(key,oldRec,res);
                }
                persistState(ids:Set()..add(key));
              }
              return;
            }
            if ( ! queryDone ) {
              initialSynced.add(key);
            }
            if ( oldRec == null ) {
              records[key] = newRecord;
              if ( queryDone ) {
                _fireRealAdd(key,newRecord,res);
              }
            }
            else {
              // FIXME: merge with local changes
              if ( queryDone ) {
                _fireRealUpdate(key,oldRec,newRecord,res);
              }
              records[key] = newRecord;
            }
            if ( queryDone ) {
              persistState(ids:Set()..add(key));
            }
          } break;
          case "REMOVE": { // can only happen after initial query
            if ( queryDone ) { // should be always true
              persistState();
            }
          } break;
          case "QUERYDONE": {
            queryDone = true;
            persistState(ids: initialSynced);
            _fireInitialSync();
          } break;
        }
      }
    });
    updateServerTS(resarr[1]);
    subsId = resarr[0];
    //print("tsync res $resarr");
  }

  /**
   * prefs and records
   */
  void persistState({Set<String> ids = null}) {
    source.persistance.persistRecords(tableName, records, ids);
    persistPrefs();
  }

  /**
   * just prefs
   */
  void persistPrefs() {
    prefs["pending"] = localTransactions;
    source.persistance.persistProps(tableName, prefs);
  }

  /**
   * called when initial update query from server is finished. initalSynced
   * contains changed keys
   */
  void _fireInitialSync() {
    print("tsync: initial sync $initialSynced");
    print("  size ${records.length}");
    if ( listener != null ) {
      listener("initialSync", {"changedKeys":initialSynced},this);
    }
  }

  /**
   * called when _del attribute has been set on an existing record
   */
  void _fireDeletion(key, rec, res) {
    print("tsync: del flag $key $rec");
    if ( listener != null ) {
      listener("deletion", {"rec":rec,"event":res},this);
    }
  }

  /**
   *  called when add or update to locally unknown record happens
   */
  void _fireRealAdd(String key, Map rec, Map event) {
    print("tsync: real add $key $rec");
    if ( listener != null ) {
      listener("add", {"rec":rec,"event":event},this);
    }
  }

  /**
   *  called when add or update to locally known record happens
   */
  void _fireRealUpdate(String key, Map oldRec, Map newRec, Map event) {
    print("tsync: real upd $key $newRec");
    if ( listener != null ) {
      listener("update", {"rec":newRec,"oldRec":oldRec,"event":event},this);
    }
  }

  /**
   * local cache has been loaded
   */
  void _fireInitialLoad() {
    print("tsync: initial load");
    print("  size ${records.length}");
    if ( listener != null ) {
      listener("initialLoad", {},this);
    }
  }

  void updateServerTS(int ts) {
    if ( prefs["serverTS"] < ts ) {
      print("updatets $ts cur: ${prefs['serverTS']}");
      prefs["serverTS"] = ts;
//      persistance.persistProps(table.name, prefs);
    }
  }

  void _fireLocalAddOrUpdate(bool add, record, Map<String, dynamic> values) {
    print("local AdUpd $add $record");
    if ( listener != null ) {
      listener("localAddUpd",{ "isAdd":add, "record": record,"changed":values},this);
    }
  }

  void _fireLocalDelete(String key) {
    print("local delete $key");
    if ( listener != null ) {
      listener("localDelete", { "key":key},this);
    }
  }

  void _fireLastUploadChanged(int i) {
    print("last upload changed $i");
    if ( listener != null ) {
      listener("uploadChnaged", { "time":i},this);
    }
  }


}
