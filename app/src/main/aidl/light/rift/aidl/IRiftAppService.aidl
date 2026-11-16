package light.rift.aidl;

import light.rift.aidl.IRiftAppServiceCallback;

interface IRiftAppService {
  int getState();
  String getProfileName();

  void registerCallback(in IRiftAppServiceCallback cb, int id);
  oneway void unregisterCallback(in IRiftAppServiceCallback cb);

  int urlTest();
}
