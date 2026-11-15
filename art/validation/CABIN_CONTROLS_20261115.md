# Cabin seating, wall screen, and ship heading

Implemented on the cabin realism branch, preserving the accepted hull and upholstery assets.

- Scenes → Spaceship cabin provides Left couch, Center chair, and Right couch.
- Couch eye targets are 1.45 m above the cabin floor. Seating accounts for current tracked eye height and faces the wall screen.
- The default Plex/library/video panel sits on the blank rear wall, opposite the pressure door. Pin to wall restores that placement. Custom saved screen placements remain intact; the previous untouched default migrates automatically.
- Ship left/right turns the cabin, center chair, screen, overlays, and viewer together in 15° increments. Exterior Earth/sky transforms stay unchanged.
- Ship heading, screen placement/size, and seat persist per environment. Reset to default restores the cabin preset.

Validation: all 300 unit tests passed; debug APK built and installed successfully on the connected Quest 3. The device diagnostic exercised all three seats, a ship turn, unchanged exterior frame, and wall placement, then restored the starting settings. No active controllers/hands were reported during that automated diagnostic; it verifies native transforms, not wearer comfort. The user accepted the cabin artwork before these controls were added. Wearer acceptance of these new controls is pending.

APK SHA256: ec01f52acbfb966f1727a8cce0582abeff5bbcb085974e6d497c2dc5e995aacd
