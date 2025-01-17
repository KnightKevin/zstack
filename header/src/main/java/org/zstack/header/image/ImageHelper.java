package org.zstack.header.image;

import java.util.HashMap;

public class ImageHelper {
    private static final HashMap<String, AbstractImageUpdate> updateImageFactory = new HashMap<>();

    static {
        buildUpdateImageFactory();
    }

    static abstract class AbstractImageUpdate {
        abstract void doUpdateImageIfVirtioIsNull(ImageInventory imageInventory);
    }

    private static void buildUpdateImageFactory() {
        AbstractImageUpdate imagePlatformIsWindows = new AbstractImageUpdate() {
            @Override
            public void doUpdateImageIfVirtioIsNull(ImageInventory inv) {
                inv.setVirtio(false);
                inv.setGuestOsType("Windows");
            }
        };
        AbstractImageUpdate imagePlatformIsWindowsVirtio = new AbstractImageUpdate() {
            @Override
            public void doUpdateImageIfVirtioIsNull(ImageInventory inv) {
                inv.setPlatform(ImagePlatform.Windows.toString());
                inv.setVirtio(true);
                inv.setGuestOsType("Windows");
            }
        };
        AbstractImageUpdate imagePlatformIsLinux = new AbstractImageUpdate() {
            @Override
            public void doUpdateImageIfVirtioIsNull(ImageInventory inv) {
                inv.setVirtio(true);
                inv.setGuestOsType("Linux");
            }
        };
        AbstractImageUpdate imagePlatformIsOther = new AbstractImageUpdate() {
            @Override
            public void doUpdateImageIfVirtioIsNull(ImageInventory inv) {
                inv.setVirtio(false);
                inv.setGuestOsType("Other");
            }
        };
        AbstractImageUpdate imagePlatformIsParavirtualization = new AbstractImageUpdate() {
            @Override
            public void doUpdateImageIfVirtioIsNull(ImageInventory inv) {
                inv.setPlatform(ImagePlatform.Other.toString());
                inv.setVirtio(true);
                inv.setGuestOsType("Other");
            }
        };

        updateImageFactory.put(ImagePlatform.Windows.toString(), imagePlatformIsWindows);
        updateImageFactory.put(ImagePlatform.WindowsVirtio.toString(), imagePlatformIsWindowsVirtio);
        updateImageFactory.put(ImagePlatform.Linux.toString(), imagePlatformIsLinux);
        updateImageFactory.put(ImagePlatform.Other.toString(), imagePlatformIsOther);
        updateImageFactory.put(ImagePlatform.Paravirtualization.toString(), imagePlatformIsParavirtualization);
    }

    public static void updateImageIfVirtioIsNull(ImageInventory imageInventory){
        updateImageFactory.get(imageInventory.getPlatform()).doUpdateImageIfVirtioIsNull(imageInventory);
    }
}
