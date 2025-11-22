# UUIDSwapper Fork
Original author: ItsTauTvyDas  
https://github.com/ItsTauTvyDas  
Original Project  
https://github.com/ItsTauTvyDas/UUIDSwapper  

I am Japanese and created this fork.
I believe this will be more accurate for those whose primary language is not English than further machine translation from English, so I've included the original text (untranslated) in README-ja.md.
This is a fork project created by a beginner coder using AI, with a different approach from the original project (focusing on manual UUID/username management across multiple servers, rather than automatic acquisition).
Please note that some messages are currently hardcoded in the author's native language.
Pull requests for multilingual support via config are welcome.
The text follows.

# Plugin Description 
The UUID and username of the user specified in the config are spoofed when logging in to all servers.  
Overrides the default settings and impersonates a different UUID and username only when logging into a specific server specified in the config.  
You can temporarily override the spoofing destination by ignoring the config using the following command.  

# Commands
The following commands are also implemented  
/changeuuid UUID in hexadecimal with a hyphen  
/changeusername Username to change  
/changenow Apply changes immediately  
/swapuuid:creload Instantly reload the configuration without rebooting  
  
If you change servers after using /changeuuid and /changeusername, the changes will be applied to the new server. Using /changenow will apply the changes to the current server.  
/changenow may also be useful if you want to quickly rejoin a server without joining the lobby for some reason.  
  
## Default configuration:  
Configuration is TOML-based and resides in `/plugins/uuid-swapper/config.toml`  
```toml
# UUID Swapper by ItsTauTvyDas

# Only works when online mode is set to false
always-use-online-uuids = false

# Set to true if you still want to swap UUIDs even when always-use-online-uuids is enabled
swap-uuids = true

# UUID swapping
# If you want to use usernames, add u: prefix
[swapped-uuids]
#"u:androidpotato" = { default = "6a7b6452-ccd1-46a8-87b0-627ff930c818" }
#"u:androidpotato" = { default = "6a7b6452-ccd1-46a8-87b0-627ff930c818", mod1 = "03b90e2e-7397-35a0-ad43-e4ab6a9d1a97", pa1 = "87b4317f-c223-3ad9-96a2-3ee4a0415f28" }
#"u:androidpotato" = { default = "", mod1 = "03b90e2e-7397-35a0-ad43-e4ab6a9d1a97", pa1 = "87b4317f-c223-3ad9-96a2-3ee4a0415f28" }


# Set custom player names
# If you use UUID that has been previously swapped, use here the original
# For usernames, add u: prefix
[custom-player-names]
#"u:androidpotato" = { default = "", pa1 = "abcdefg", mod1 = "OfflinePotato" }
#"u:.androidP2653" = {default = "bedrockpotato1"}
#"00000000-0000-0000-0009-01f55a02cedb" = {default = "bedrockpotato1"}
```  
Note that the syntax is slightly different from the original project (it's nested to support multiple servers), so copying the config file as is may result in an error.  
  
Finally, thank you for allowing me to publish the fork.