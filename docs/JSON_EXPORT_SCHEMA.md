# OPTC Box Manager JSON export schema

This is a new, documented schema for the Android Box Manager. The original OPTC Box Exporter master branch did not define a persistent JSON box-export format.

## Version 1

~~~json
{
  "schemaVersion": 1,
  "application": "OPTC Box Manager",
  "profile": {
    "name": "Global Main",
    "server": "Global"
  },
  "characters": [
    {
      "characterId": 12345,
      "name": "Character display name",
      "quantity": 2,
      "type": "STR",
      "classes": ["Fighter", "Free Spirit"],
      "stars": "6",
      "portrait": "portraits/12345.png"
    }
  ],
  "sourceScreenshots": ["screenshot_001.jpg"]
}
~~~

The characters array contains one record per character ID. Quantity represents the number of distinct physical copies in that profile. Different IDs are never merged solely because their display names match. The Android collection currently increments quantity when a user manually adds another copy of the same ID.

The Android app writes the profile and screenshot manifest plus any character records currently in that profile. Character records may be added manually from the shared static reference catalogue; automatic screenshot recognition and overlap review are not yet connected. A manually curated export must not be presented as a completed automatic box scan.

Portrait paths are relative to the export bundle only when the portrait asset is included. Future export code must either include the referenced portraits or omit the portrait field.
