# Steps to use (This has serious security concerns to tails threat model only run when you need to access haveno)

## 1. Enable persistent storage and admin password before starting tails

## 2. Get your haveno deb file in persistent storage, currently most people use haveno-reto (amd64 version for tails)

## 3. Edit the path to the haveno deb file if necessary then run ```sudo ./haveno-install.sh```
## 4. As amnesia run ```source ~/.bashrc``` 
## 5. Start haveno using ```haveno-tails```

## You will need to run this script after each reset, but your data will be saved persistently in /home/amnesia/Persistence/Haveno-reto
