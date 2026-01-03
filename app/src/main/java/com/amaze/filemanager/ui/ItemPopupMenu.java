/*
 * Copyright (C) 2014-2024 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam<emmanuelbendavid@gmail.com>, Raymond Lai <airwave209gt at gmail.com> and Contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.ui;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amaze.filemanager.application.AppConfig;
import com.amaze.filemanager.R;
import com.amaze.filemanager.adapters.data.LayoutElementParcelable;
import com.amaze.filemanager.asynchronous.services.EncryptService;
import com.amaze.filemanager.fileoperations.exceptions.ShellNotRunningException;
import com.amaze.filemanager.fileoperations.filesystem.OpenMode;
import com.amaze.filemanager.fileoperations.exceptions.ShellNotRunningException;
import com.amaze.filemanager.filesystem.HybridFileParcelable;
import com.amaze.filemanager.filesystem.PasteHelper;
import com.amaze.filemanager.filesystem.files.EncryptDecryptUtils;
import com.amaze.filemanager.filesystem.files.FileUtils;
import com.amaze.filemanager.filesystem.root.MountIsoCommand;
import com.amaze.filemanager.ui.activities.MainActivity;
import com.amaze.filemanager.ui.dialogs.EncryptAuthenticateDialog;
import com.amaze.filemanager.ui.dialogs.EncryptWithPresetPasswordSaveAsDialog;
import com.amaze.filemanager.ui.dialogs.GeneralDialogCreation;
import com.amaze.filemanager.ui.fragments.MainFragment;
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants;
import com.amaze.filemanager.ui.provider.UtilitiesProvider;
import com.amaze.filemanager.utils.DataUtils;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

/**
 * This class contains the functionality of the PopupMenu for each file in the MainFragment
 *
 * @author Emmanuel on 25/5/2017, at 16:39. Edited by bowiechen on 2019-10-19.
 */
public class ItemPopupMenu extends PopupMenu implements PopupMenu.OnMenuItemClickListener {

  private static final Logger LOG = LoggerFactory.getLogger(ItemPopupMenu.class);

  @NonNull private final Context context;
  @NonNull private final MainActivity mainActivity;
  @NonNull private final UtilitiesProvider utilitiesProvider;
  @NonNull private final MainFragment mainFragment;
  @NonNull private final SharedPreferences sharedPrefs;
  @NonNull private final LayoutElementParcelable rowItem;
  private final int accentColor;

  public ItemPopupMenu(
      @NonNull Context c,
      @NonNull MainActivity ma,
      @NonNull UtilitiesProvider up,
      @NonNull MainFragment mainFragment,
      @NonNull LayoutElementParcelable ri,
      @NonNull View anchor,
      @NonNull SharedPreferences sharedPreferences) {
    super(c, anchor);

    context = c;
    mainActivity = ma;
    utilitiesProvider = up;
    this.mainFragment = mainFragment;
    sharedPrefs = sharedPreferences;
    rowItem = ri;
    accentColor = mainActivity.getAccent();

    setOnMenuItemClickListener(this);
  }

  @Override
  public boolean onMenuItemClick(MenuItem item) {
    if (item.getItemId() == R.id.about) {
      GeneralDialogCreation.showPropertiesDialogWithPermissions(
          (rowItem).generateBaseFile(),
          rowItem.permissions,
          mainActivity,
          mainFragment,
          mainActivity.isRootExplorer(),
          utilitiesProvider.getAppTheme());
      return true;
    } else if (item.getItemId() == R.id.share) {
      switch (rowItem.getMode()) {
        case DROPBOX:
        case BOX:
        case GDRIVE:
        case ONEDRIVE:
          FileUtils.shareCloudFile(rowItem.desc, rowItem.getMode(), context);
          break;
        default:
          ArrayList<File> arrayList = new ArrayList<>();
          arrayList.add(new File(rowItem.desc));
          FileUtils.shareFiles(
              arrayList, mainActivity, utilitiesProvider.getAppTheme(), accentColor);
          break;
      }
      return true;
    } else if (item.getItemId() == R.id.mount_image) {
      if (ensureMountPrerequisites()) {
        new MountImageTask(true).executeOnExecutor(
            AsyncTask.THREAD_POOL_EXECUTOR, rowItem.desc);
      }
      return true;
    } else if (item.getItemId() == R.id.unmount_image) {
      if (ensureMountPrerequisites()) {
        new MountImageTask(false).executeOnExecutor(
            AsyncTask.THREAD_POOL_EXECUTOR, rowItem.desc);
      }
      return true;
    } else if (item.getItemId() == R.id.rename) {
      mainFragment.rename(rowItem.generateBaseFile());
      return true;
    } else if (item.getItemId() == R.id.cpy || item.getItemId() == R.id.cut) {
      int op =
          item.getItemId() == R.id.cpy ? PasteHelper.OPERATION_COPY : PasteHelper.OPERATION_CUT;
      PasteHelper pasteHelper =
          new PasteHelper(
              mainActivity, op, new HybridFileParcelable[] {rowItem.generateBaseFile()});
      mainActivity.setPaste(pasteHelper);
      return true;
    } else if (item.getItemId() == R.id.ex) {
      mainActivity.mainActivityHelper.extractFile(new File(rowItem.desc));
      return true;
    } else if (item.getItemId() == R.id.book) {
      DataUtils dataUtils = DataUtils.getInstance();
      if (dataUtils.addBook(new String[] {rowItem.title, rowItem.desc}, true)) {
        mainActivity.getDrawer().refreshDrawer();
        Toast.makeText(
                mainFragment.getActivity(),
                mainFragment.getString(R.string.bookmarks_added),
                Toast.LENGTH_LONG)
            .show();
      } else {
        Toast.makeText(
                mainFragment.getActivity(),
                mainFragment.getString(R.string.bookmark_exists),
                Toast.LENGTH_LONG)
            .show();
      }
      return true;
    } else if (item.getItemId() == R.id.delete) {
      ArrayList<LayoutElementParcelable> positions = new ArrayList<>();
      positions.add(rowItem);
      GeneralDialogCreation.deleteFilesDialog(
          context, mainActivity, positions, utilitiesProvider.getAppTheme());
      return true;
    } else if (item.getItemId() == R.id.restore) {
      ArrayList<LayoutElementParcelable> p2 = new ArrayList<>();
      p2.add(rowItem);
      GeneralDialogCreation.restoreFilesDialog(
          context, mainActivity, p2, utilitiesProvider.getAppTheme());
      return true;
    } else if (item.getItemId() == R.id.open_with) {
      boolean useNewStack =
          sharedPrefs.getBoolean(PreferencesConstants.PREFERENCE_TEXTEDITOR_NEWSTACK, false);

      if (OpenMode.DOCUMENT_FILE.equals(rowItem.getMode())) {

        @Nullable Uri fullUri = rowItem.generateBaseFile().getFullUri();

        if (fullUri != null) {

          DocumentFile documentFile = DocumentFile.fromSingleUri(context, fullUri);

          if (documentFile != null) {
            FileUtils.openWith(documentFile, mainActivity, useNewStack);
            return true;
          }
        }
      }

      FileUtils.openWith(new File(rowItem.desc), mainActivity, useNewStack);

      return true;
    } else if (item.getItemId() == R.id.encrypt) {
      final Intent encryptIntent = new Intent(context, EncryptService.class);
      encryptIntent.putExtra(EncryptService.TAG_OPEN_MODE, rowItem.getMode().ordinal());
      encryptIntent.putExtra(EncryptService.TAG_SOURCE, rowItem.generateBaseFile());

      final EncryptDecryptUtils.EncryptButtonCallbackInterface
          encryptButtonCallbackInterfaceAuthenticate =
              new EncryptDecryptUtils.EncryptButtonCallbackInterface() {
                @Override
                public void onButtonPressed(Intent intent, String password)
                    throws GeneralSecurityException, IOException {
                  EncryptDecryptUtils.startEncryption(
                      context, rowItem.generateBaseFile().getPath(), password, intent);
                }
              };

      final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

      if (!preferences
          .getString(
              PreferencesConstants.PREFERENCE_CRYPT_MASTER_PASSWORD,
              PreferencesConstants.PREFERENCE_CRYPT_MASTER_PASSWORD_DEFAULT)
          .equals("")) {
        EncryptWithPresetPasswordSaveAsDialog.show(
            context,
            encryptIntent,
            mainActivity,
            PreferencesConstants.ENCRYPT_PASSWORD_MASTER,
            encryptButtonCallbackInterfaceAuthenticate);
      } else if (preferences.getBoolean(
          PreferencesConstants.PREFERENCE_CRYPT_FINGERPRINT,
          PreferencesConstants.PREFERENCE_CRYPT_FINGERPRINT_DEFAULT)) {
        EncryptWithPresetPasswordSaveAsDialog.show(
            context,
            encryptIntent,
            mainActivity,
            PreferencesConstants.ENCRYPT_PASSWORD_FINGERPRINT,
            encryptButtonCallbackInterfaceAuthenticate);
      } else {
        EncryptAuthenticateDialog.show(
            context,
            encryptIntent,
            mainActivity,
            utilitiesProvider.getAppTheme(),
            encryptButtonCallbackInterfaceAuthenticate);
      }
      return true;
    } else if (item.getItemId() == R.id.decrypt) {
      EncryptDecryptUtils.decryptFile(
          context,
          mainActivity,
          mainFragment,
          mainFragment.getMainFragmentViewModel().getOpenMode(),
          rowItem.generateBaseFile(),
          rowItem.generateBaseFile().getParent(context),
          utilitiesProvider,
          false);
      return true;
    } else if (item.getItemId() == R.id.compress) {
      GeneralDialogCreation.showCompressDialog(
          mainActivity,
          rowItem.generateBaseFile(),
          mainActivity.getCurrentMainFragment().getMainFragmentViewModel().getCurrentPath());
      return true;
    } else if (item.getItemId() == R.id.mount_image) {
      handleMountImage();
      return true;
    } else if (item.getItemId() == R.id.unmount_image) {
      handleUnmountImage();
      return true;
    } else if (item.getItemId() == R.id.return_select) {
      mainFragment.returnIntentResults(new HybridFileParcelable[] {rowItem.generateBaseFile()});
      return true;
    }
    return false;
  }

  private void handleMountImage() {
    if (!mainActivity.isRootExplorer()) {
      AppConfig.toast(context, R.string.mount_image_requires_root);
      return;
    }

    AppConfig.getInstance()
        .runInBackground(
            () -> {
              try {
                if (!MountIsoCommand.INSTANCE.supportsLoopDevices()) {
                  AppConfig.toast(context, R.string.loop_device_not_supported);
                  return;
                }

                String mountedPath = MountIsoCommand.INSTANCE.getMountedPath(rowItem.desc);
                if (mountedPath != null) {
                  AppConfig.toast(
                      context,
                      context.getString(R.string.image_mounted_to, mountedPath));
                  return;
                }

                String mountPoint = MountIsoCommand.INSTANCE.getSuggestedMountPoint(rowItem.desc);
                String resolvedMountPoint =
                    MountIsoCommand.INSTANCE.mountImage(rowItem.desc, mountPoint);
                if (resolvedMountPoint != null) {
                  AppConfig.toast(
                      context,
                      context.getString(R.string.image_mounted_to, resolvedMountPoint));
                } else {
                  AppConfig.toast(context, R.string.mount_image_failed);
                }
              } catch (ShellNotRunningException e) {
                LOG.error("Root shell not available for mounting {}", rowItem.desc, e);
                AppConfig.toast(context, R.string.mount_image_requires_root);
              } catch (Exception e) {
                LOG.error("Unable to mount image {}", rowItem.desc, e);
                AppConfig.toast(context, R.string.mount_image_failed);
              }
            });
  }

  private void handleUnmountImage() {
    if (!mainActivity.isRootExplorer()) {
      AppConfig.toast(context, R.string.mount_image_requires_root);
      return;
    }

    AppConfig.getInstance()
        .runInBackground(
            () -> {
              try {
                String mountedPath = MountIsoCommand.INSTANCE.getMountedPath(rowItem.desc);
                if (mountedPath == null) {
                  AppConfig.toast(context, R.string.image_not_mounted);
                  return;
                }

                boolean unmounted = MountIsoCommand.INSTANCE.unmountImage(rowItem.desc);
                if (unmounted) {
                  AppConfig.toast(context, R.string.image_unmounted);
                } else {
                  AppConfig.toast(context, R.string.unmount_image_failed);
                }
              } catch (ShellNotRunningException e) {
                LOG.error("Root shell not available for unmounting {}", rowItem.desc, e);
                AppConfig.toast(context, R.string.mount_image_requires_root);
              } catch (Exception e) {
                LOG.error("Unable to unmount image {}", rowItem.desc, e);
                AppConfig.toast(context, R.string.unmount_image_failed);
              }
            });
  }
}
