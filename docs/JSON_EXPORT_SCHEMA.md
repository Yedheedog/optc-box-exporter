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

The characters array contains one record per character ID. Quantity represents the number of distinct physical copies in that profile. Different IDs are never merged solely because their display names match.

The current Android scaffold writes the profile and screenshot manifest but leaves characters empty until screenshot recognition and overlap review are implemented. It must not claim an empty export is a completed box scan.

Portrait paths are relative to the export bundle only when the portrait asset is included. Future export code must either include the referenced portraits or omit the portrait field.
