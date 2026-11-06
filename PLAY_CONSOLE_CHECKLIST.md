# Play Console answers (draft: adjust to the real app)
**Photo/video permission declaration:** Core function is scanning the entire library by age/size to find the largest old media and offload it to user-chosen storage after checksum verification. The Android Photo Picker cannot enumerate or sort the whole library, so it is not sufficient. A picker-based fallback is not yet included; consider adding one.
**Foreground service (dataSync):** Used only while copying user-selected files to external storage; shows an ongoing notification with Cancel.
**Data Safety:** Data collected: none. Data shared: none. (Purchases are processed by Google Play.) Update if you add analytics.
**In-app product:** create "premium_lifetime" (one-time) before testing billing; test with license testers.
**Other:** content rating questionnaire, target audience (not for children), privacy policy URL, app icon 512x512, feature graphic, 2+ phone screenshots.
