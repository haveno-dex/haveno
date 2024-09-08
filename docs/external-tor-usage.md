# Using External Tor with Haveno

## Setting up Tor:

### 1. Download and Install Tor
#### Linux
You can install Tor through your system's package manager, or directly by going to torproject.org. Note that your package manager's version of tor might be outdated or non-GNU (see below note).
#### MacOS
You can install Tor through Homebrew (brew install tor), or directly by going to torproject.org. Note that the Homebrew version of tor might be outdated or non-GNU (see note below).
#### Windows
You can install Tor directly by going to torproject.org.
> For best results, ensure that your version of tor matches the one currently used by Haveno. To improve loading times, it is highly recommended to use the GNU build, as it has support for POW. You can check if your build is GNU by running tor --version and looking for a response mentioning GNU.

### 2. Add the following lines to your torrc:
```
controlPort=9051
CookieAuthentication 1
CookieAuthFile <path to cookie auth file>
```
> The "CookieAuthFile" path should be in a relatively stable and safe place. A good place to put it is in the same directory as torrc.

### The location of your torrc file varies depending on your system:

#### Linux
```/etc/tor/torrc```
#### MacOS
```/usr/local/etc/tor/torrc```
#### Windows
```\Browser\TorBrowser\Data\Tor\torrc``` (when using the Tor Browser Bundle)

> The above locations are best guesses, and may be incorrect depending on your installation method or platform distribution. In the case that you cannot find the correct directory to place your torrc, tor can be ran in the following format to use a custom torrc: ```tor -f <torrc>```

### 3. Stop any currently running tor processes
#### Linux/MacOS
```killall tor```
#### Windows
```Stop-Process -Name tor```
### 4. Start Tor
```tor```
## Using External Tor with Haveno:
Run the Haveno binary with the following extra flags:
```
--torControlPort=9051 --torControlCookieFile=<path to cookie auth file> --torControlUseSafeCookieAuth=true
```
> Replace the ```<path to cookie auth file>``` with the location you specified in step 2.

> Depending on how your version of Haveno was distributed, you may not have direct access to the haveno binary. In this case, you should look inside your Haveno distribution for the binary, and then run the binary using the above flags. For example, on MacOS the binary will be located within the app at Haveno.app/Contents/MacOS/Haveno, or something similar.
