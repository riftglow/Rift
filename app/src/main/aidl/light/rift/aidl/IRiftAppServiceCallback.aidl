package light.rift.aidl;

import light.rift.aidl.SpeedDisplayData;
import light.rift.aidl.TrafficData;

oneway interface IRiftAppServiceCallback {
  void stateChanged(int state, String profileName, String msg);
  void cbSpeedUpdate(in SpeedDisplayData stats);
  void cbTrafficUpdate(in TrafficData stats);
  void cbSelectorUpdate(long id);
}
