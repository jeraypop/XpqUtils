## gradle
### Step 1. Add the JitPack repository to your build file
#### Add it in your root settings.gradle at the end of repositories:
	dependencyResolutionManagement {
		repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
		repositories {
			mavenCentral()
			maven { url 'https://jitpack.io' }
		}
	}
### Step 2. Add the dependency
	dependencies {
	        implementation 'com.github.jeraypop:XpqUtils:Tag'
	}

### Step 3. 配置赞赏
        Donate.init(
            this,
            DonateConfig.Builder("payCode", R.mipmap.ic_zhifubao, R.mipmap.ic_weixin).build()
)

## gradle.kts
### Step 1. Add the JitPack repository to your build file
#### Add it in your settings.gradle.kts at the end of repositories:
	dependencyResolutionManagement {
		repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
		repositories {
			mavenCentral()
			maven { url = uri("https://jitpack.io") }
		}
	}
### Step 2. Add the dependency
	dependencies {
	        implementation("com.github.jeraypop:XpqUtils:Tag")
	}

### PS: 如果不需要额外的权限申请,可在你的项目清单文件中移除
    <uses-permission
        android:name="android.permission.READ_MEDIA_AUDIO"
        tools:node="remove" />
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        tools:node="remove" />

### JitPack
[![](https://jitpack.io/v/jeraypop/XpqUtils.svg)](https://jitpack.io/#jeraypop/XpqUtils)

