# Using External Tor with Haveno
>Looking to setup external Tor for a seednode? Go [here](https://github.com/haveno-dex/haveno/blob/master/docs/deployment-guide.md#install-tor) for tailored instructions.
## Setting up Tor:

### 1. Download and Install Tor
> If you already have Tor, you can skip this step; ensure it has POW support before proceeding.

Regardless of platform, the recommended way to download and install Tor is by going to [the Tor Project's website](https://www.torproject.org/download/) and following their instructions.
> For best results, ensure that your version of tor matches the one currently used by Haveno. To improve loading times, it is highly recommended to use the GNU build, as it has support for POW. You can check if your build is GNU by running tor --version and looking for a response mentioning GNU.

### 2. Add the following lines to your torrc file:
```
controlPort=9051
CookieAuthentication 1
CookieAuthFile <tor config path>/control_auth_cookie
```
> The "CookieAuthFile" will not exist until after Tor is ran, so running Tor once should create it if it does not exist.
#### Your torrc file is within your tor config path, which varies depending on your system:

##### Linux
```/etc/tor/torrc```
##### MacOS
```/usr/local/etc/tor/torrc```
##### Windows
```\Browser\TorBrowser\Data\Tor\torrc``` (when using the Tor Browser Bundle)

> The above locations are best guesses, and may be incorrect depending on your installation method or platform distribution. In the case that you cannot find the correct directory to place your torrc or you suspect the desired directory is not being used, tor can be ran with a custom torrc: ```tor -f <path to torrc>```

### 3. Stop any currently running tor processes
#### Linux/MacOS
```killall tor```
#### Windows
```Stop-Process -Name tor```
>on Linux/MacOS, it may be wise to also run ```sudo killall tor``` in case the process is owned by root on accident. On systems with multiple concurrent users, properly killing tor might require running ```killall tor``` on all relevant users.
### 4. Start Tor
To start tor, simply run ```tor```, or ```tor -f <path to torrc>``` for a custom configuration.
>On Linux/MacOS, the user running tor must be a member of the "tor" usergroup.

## Using External Tor with Haveno:
Run the Haveno binary with the following extra flags:
```
--torControlPort=9051 --torControlCookieFile=<tor config path>/control_auth_cookie --torControlUseSafeCookieAuth=true
```
> Replace the ```<tor config path>``` with the location specified in step 2.

> Depending on how your version of Haveno was distributed, you may not have direct access to the Haveno binary. In this case, you should look inside your Haveno distribution for the binary, and then run the binary using the above flags. For example, on MacOS the binary will be located within the app at Haveno.app/Contents/MacOS/Haveno, or something similar. 
