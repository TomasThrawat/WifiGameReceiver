// AIDL contract for the Shizuku UserService.
// UinputUserService implements this and runs at shell UID.
package com.tomasthrawat.wifigamereceiver;

interface IUinputService {
    void registerDevice();
    void sendButton(int keyCode, boolean down);
    void sendDpad(int x, int y);
    void sendJoystick(int x, int y);
    void unregisterDevice();
    void destroy();
}
