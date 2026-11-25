package light.rift.aidl;

import light.rift.aidl.SpeedDisplayData;
import light.rift.aidl.TrafficData;

oneway interface IRiftAppServiceCallback {
  void stateChanged(int state, String profileName, String msg);
  void cbSpeedUpdate(in SpeedDisplayData stats);
  void cbTrafficUpdate(in TrafficData stats);
  void cbSelectorUpdate(long id);
  // Auto speed test (step ②, 2026-11-25): per-node progress. state: 1 testing / 2 queued / 0 done (clear);
  // profileId = -1 means the whole round finished.
  void cbTestProgress(long profileId, int state);
}
