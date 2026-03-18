package me.ocv.partyup.objects;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

public class CustomFile implements Parcelable {
    public static final Creator<CustomFile> CREATOR = new Creator<CustomFile>() {
        @NonNull
        @Contract("_ -> new")
        @Override
        public CustomFile createFromParcel(Parcel in) {
            return new CustomFile(in);
        }

        @NonNull
        @Contract(value = "_ -> new", pure = true)
        @Override
        public CustomFile[] newArray(int size) {
            return new CustomFile[size];
        }
    };

    public Uri handle;
    public Long size;
    public String name;
    public String full_url;
    public String share_url;
    public String desc;
    public String content;
    public String mime;
    public String ext;

    // Parcelable Implementations
    public CustomFile() {
    }

    protected CustomFile(@NonNull Parcel in) {
        handle = in.readParcelable(Uri.class.getClassLoader());
        if (in.readByte() == 0) {
            size = null;
        } else {
            size = in.readLong();
        }
        name = in.readString();
        full_url = in.readString();
        share_url = in.readString();
        desc = in.readString();
        content = in.readString();
        mime = in.readString();
        ext = in.readString();
    }

    public boolean isSharable() {
        boolean hasUrl = (share_url != null && !share_url.isEmpty()) ||
                (full_url != null && !full_url.isEmpty());
        boolean isText = "text/plain".equals(mime);

        return hasUrl && !isText;
    }

    public String getBestUrl() {
        if (!isSharable())
            return "";
        if (share_url != null && !share_url.isEmpty()) {
            return share_url;
        }
        if (full_url != null && !full_url.isEmpty()) {
            return full_url;
        }
        return "";
    }

    @NonNull
    @Override
    public String toString() {
        return "CustomFile{" +
                "handle=" + handle +
                ", size=" + size +
                ", name=" + name +
                ", full_url=" + full_url +
                ", share_url=" + share_url +
                ", content=" + content +
                ", mime=" + mime +
                ", ext=" + ext +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int i) {
        parcel.writeParcelable(handle, i);
        if (size == null) {
            parcel.writeByte((byte) 0);
        } else {
            parcel.writeByte((byte) 1);
            parcel.writeLong(size);
        }
        parcel.writeString(name);
        parcel.writeString(full_url);
        parcel.writeString(share_url);
        parcel.writeString(desc);
        parcel.writeString(content);
        parcel.writeString(mime);
        parcel.writeString(ext);
    }
}