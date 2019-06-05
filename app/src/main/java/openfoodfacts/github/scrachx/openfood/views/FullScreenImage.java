package openfoodfacts.github.scrachx.openfood.views;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.*;
import butterknife.BindView;
import butterknife.OnClick;
import butterknife.OnItemSelected;
import com.github.chrisbanes.photoview.PhotoView;
import com.github.chrisbanes.photoview.PhotoViewAttacher;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;
import com.theartofdev.edmodo.cropper.CropImage;
import com.theartofdev.edmodo.cropper.CropImageActivity;
import io.reactivex.SingleObserver;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import openfoodfacts.github.scrachx.openfood.R;
import openfoodfacts.github.scrachx.openfood.fragments.BaseFragment;
import openfoodfacts.github.scrachx.openfood.images.*;
import openfoodfacts.github.scrachx.openfood.jobs.FileDownloader;
import openfoodfacts.github.scrachx.openfood.jobs.PhotoReceiverHandler;
import openfoodfacts.github.scrachx.openfood.models.Product;
import openfoodfacts.github.scrachx.openfood.models.ProductImageField;
import openfoodfacts.github.scrachx.openfood.models.State;
import openfoodfacts.github.scrachx.openfood.network.CommonApiManager;
import openfoodfacts.github.scrachx.openfood.network.OpenFoodAPIClient;
import openfoodfacts.github.scrachx.openfood.utils.ImageUploadListener;
import openfoodfacts.github.scrachx.openfood.utils.LocaleHelper;
import openfoodfacts.github.scrachx.openfood.utils.SwipeDetector;
import openfoodfacts.github.scrachx.openfood.utils.Utils;
import openfoodfacts.github.scrachx.openfood.views.adapters.LanguageDataAdapter;
import org.apache.commons.lang3.StringUtils;
import pl.aprilapps.easyphotopicker.EasyImage;

import java.io.File;
import java.util.*;

import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static openfoodfacts.github.scrachx.openfood.utils.Utils.MY_PERMISSIONS_REQUEST_CAMERA;
import static openfoodfacts.github.scrachx.openfood.utils.Utils.MY_PERMISSIONS_REQUEST_STORAGE;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

/**
 * Activity to display/edit product images
 */
public class FullScreenImage extends BaseActivity implements PhotoReceiver {
    private static final int RESULTCODE_MODIFIED = 1;
    private static final int REQUEST_EDIT_IMAGE_AFTER_LOGIN = 1;
    private static final int REQUEST_ADD_IMAGE_AFTER_LOGIN = 2;
    private static final int REQUEST_CHOOSE_IMAGE_AFTER_LOGIN = 3;
    static final int REQUEST_EDIT_IMAGE = 1000;
    @BindView(R.id.imageViewFullScreen)
    PhotoView mPhotoView;
    @BindView(R.id.btnEditImage)
    Button editButton;
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.textInfo)
    TextView textInfo;
    @BindView(R.id.btnChooseDefaultLanguage)
    TextView btnChooseDefaultLanguage;
    @BindView(R.id.progressBar)
    ProgressBar progressBar;
    @BindView(R.id.comboLanguages)
    Spinner comboLanguages;
    @BindView(R.id.comboImageType)
    Spinner comboImageType;
    private PhotoViewAttacher mAttacher;
    private OpenFoodAPIClient client;
    private File lastViewedImage;
    private static final List<ProductImageField> TYPE_IMAGE = Arrays.asList(ProductImageField.FRONT, ProductImageField.INGREDIENTS, ProductImageField.NUTRITION);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        client = new OpenFoodAPIClient(this);
        setContentView(R.layout.activity_full_screen_image);

        Intent intent = getIntent();

        Product product = (Product) intent.getSerializableExtra(ImageKeyHelper.PRODUCT);
        boolean canEdit = product != null;
        editButton.setVisibility(canEdit ? View.VISIBLE : View.INVISIBLE);

        mAttacher = new PhotoViewAttacher(mPhotoView);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            //delaying the transition until the view has been laid out
            postponeEnterTransition();
        }
        new SwipeDetector(mPhotoView).setOnSwipeListener((v, swipeType) -> {
            if (swipeType == SwipeDetector.SwipeTypeEnum.LEFT_TO_RIGHT) {
                incrementImageType(-1);
            } else if (swipeType == SwipeDetector.SwipeTypeEnum.RIGHT_TO_LEFT) {
                incrementImageType(1);
            } else if (swipeType == SwipeDetector.SwipeTypeEnum.TOP_TO_BOTTOM) {
                onRefresh(true);
            } else {
                stopRefresh();
            }
        });
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.simple_spinner_item_white, generateImageTypeNames());
        adapter.setDropDownViewResource(android.R.layout.simple_list_item_single_choice);
        comboImageType.setAdapter(adapter);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        loadLanguage();
        updateToolbarTitle(getProduct());
        onRefresh(false);
        comboImageType.setSelection(TYPE_IMAGE.indexOf(getSelectedType()));
    }

    private List<String> generateImageTypeNames() {
        List<String> images = new ArrayList<>();
        for (ProductImageField type : TYPE_IMAGE) {
            images.add(getResources().getString(ImageKeyHelper.getResourceId(type)));
        }
        return images;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    public static boolean isImageModified(int requestCode, int resultCode) {
        return requestCode == REQUEST_EDIT_IMAGE && resultCode == FullScreenImage.RESULTCODE_MODIFIED;
    }

    private void incrementImageType(int inc) {
        stopRefresh();
        int newPosition = comboImageType.getSelectedItemPosition() + inc;
        final int count = comboImageType.getAdapter().getCount();
        if (newPosition < 0) {
            newPosition = count - 1;
        } else {
            newPosition = newPosition % count;
        }
        comboImageType.setSelection(newPosition, true);
    }

    private void loadLanguage() {
        Product product = getProduct();
        if (product == null) {
            return;
        }
        //we load all available languages for product/type
        String currentLanguage = getCurrentLanguage();
        final ProductImageField productImageField = getSelectedType();
        final Set<String> addedLanguages = new HashSet<>(product.getAvailableLanguageForImage(productImageField, ImageSize.DISPLAY));
        final List<LocaleHelper.LanguageData> languageForImage = LocaleHelper.getLanguageData(addedLanguages, true);
        int selectedIndex = LocaleHelper.find(languageForImage, currentLanguage);
        if (selectedIndex < 0) {
            addedLanguages.add(currentLanguage);
            languageForImage.add(LocaleHelper.getLanguageData(currentLanguage, false));
        }
        String[] localeValues = getResources().getStringArray(R.array.languages_array);
        List<String> otherNotSupportedCode = new ArrayList<>();
        for (String local : localeValues) {
            if (!addedLanguages.contains(local)) {
                otherNotSupportedCode.add(local);
            }
        }
        languageForImage.addAll(LocaleHelper.getLanguageData(otherNotSupportedCode, false));
        LanguageDataAdapter adapter = new LanguageDataAdapter(this, R.layout.simple_spinner_item_white, languageForImage);
        adapter.setDropDownViewResource(android.R.layout.simple_list_item_single_choice);
        comboLanguages.setAdapter(adapter);
        selectedIndex = LocaleHelper.find(languageForImage, currentLanguage);
        if (selectedIndex >= 0) {
            comboLanguages.setSelection(selectedIndex);
        }
        updateLanguageStatus();
        updateSelectDefaultLanguageAction();
    }

    /**
     * Use to warn the user that there is no image for the selected image.
     */
    private boolean updateLanguageStatus() {
        final ProductImageField serializableExtra = getSelectedType();
        String imageUrl = getCurrentImageUrl();
        String languageUsedByImage = ImageKeyHelper.getLanguageCodeFromUrl(serializableExtra, imageUrl);
        String language = getCurrentLanguage();
        //if the language of the displayed image is not the same that the language in this activity
        //we use the language of the image
        boolean languageSupported = language.equals(languageUsedByImage);
        if (languageSupported) {
            textInfo.setText(null);
            textInfo.setTextColor(ContextCompat.getColor(this, R.color.white));
        } else {
            textInfo.setText(R.string.image_not_defined_for_language);
            textInfo.setTextColor(ContextCompat.getColor(this, R.color.orange));
        }
        editButton.setVisibility(languageSupported ? View.VISIBLE : View.GONE);
        return languageSupported;
    }

    private String getCurrentLanguage() {
        return getIntent().getStringExtra(ImageKeyHelper.LANGUAGE);
    }

    private void updateToolbarTitle(Product product) {
        if (product != null) {
            final String languageCode = getCurrentLanguage();
            changeToolBarTitle(product.getProductName(languageCode));
            //if the name in the selected language is not present, we try to download it...
            if (!product.hasProductNameIn(languageCode)) {
                final String key = "product_name_" + languageCode;
                CommonApiManager.getInstance().getOpenFoodApiService()
                    .getExistingProductDetails(product.getCode(), key, Utils.getUserAgent(Utils.HEADER_USER_AGENT_SEARCH))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new SingleObserver<State>() {
                        @Override
                        public void onSubscribe(Disposable d) {
                            //nothing to do
                        }

                        @Override
                        public void onError(Throwable e) {
                            //nothing to do
                        }

                        @Override
                        public void onSuccess(State state) {
                            if (state.getStatus() == 1) {
                                final String productName = state.getProduct().getProductName(languageCode);
                                if (StringUtils.isNotBlank(productName)) {
                                    product.getAdditionalProperties().put(key, productName);
                                    changeToolBarTitle(productName);
                                }
                            }
                        }
                    });
            }
        }
    }

    private void changeToolBarTitle(String productName) {
        toolbar.setTitle(productName + " / " + comboImageType.getSelectedItem().toString());
    }

    private void onRefresh(boolean reloadProduct) {
        String imageUrl = getCurrentImageUrl();
        if (reloadProduct || imageUrl == null) {
            reloadProduct();
        } else {
            loadImage(imageUrl);
        }
    }

    private void loadImage(String imageUrl) {
        if (isNotEmpty(imageUrl)) {
            startRefresh(getString(R.string.txtLoading));
            Picasso.with(this)
                .load(imageUrl)
                .into(mPhotoView, new Callback() {
                    @Override
                    public void onSuccess() {
                        mAttacher.update();
                        scheduleStartPostponedTransition(mPhotoView);
                        mPhotoView.setVisibility(View.VISIBLE);
                        stopRefresh();
                    }

                    @Override
                    public void onError() {
                        mPhotoView.setVisibility(View.VISIBLE);
                        Toast.makeText(FullScreenImage.this, getResources().getString(R.string.txtConnectionError), Toast.LENGTH_LONG).show();
                        stopRefresh();
                    }
                });
        }else{
            mPhotoView.setImageDrawable(null);
            stopRefresh();
        }
    }

    /**
     * Reload the product, update the image and the language
     */
    private void reloadProduct() {
        if (isFinishing()) {
            return;
        }
        Product product = getProduct();
        if (product != null) {
            startRefresh(getString(R.string.loading_product, "..."));
            client.getProduct(product.getCode(), this, newState -> {
                final Product newStateProduct = newState.getProduct();
                boolean imageReloaded = false;
                if (newStateProduct != null) {
                    updateToolbarTitle(newStateProduct);
                    String imageUrl = getCurrentImageUrl();
                    getIntent().putExtra(ImageKeyHelper.PRODUCT, newStateProduct);
                    final String newImageUrl = getImageUrlToDisplay(newStateProduct);
                    loadLanguage();
                    if (imageUrl == null || !imageUrl.equals(newImageUrl)) {
                        getIntent().putExtra(ImageKeyHelper.IMAGE_URL, newImageUrl);
                        loadImage(newImageUrl);
                        imageReloaded = true;
                    }
                } else {
                    if (StringUtils.isNotBlank(newState.getStatusVerbose())) {
                        Toast.makeText(FullScreenImage.this, newState.getStatusVerbose(), Toast.LENGTH_LONG).show();
                    }
                }
                if (!imageReloaded) {
                    stopRefresh();
                }
            });
        }
    }

    private String getImageUrlToDisplay(Product product) {
        return product.getSelectedImage(getCurrentLanguage(), getSelectedType(),
            ImageSize.DISPLAY);
    }

    private String getCurrentImageUrl() {
        return getIntent().getStringExtra(ImageKeyHelper.IMAGE_URL);
    }

    private void stopRefresh() {
        progressBar.setVisibility(View.GONE);
        updateLanguageStatus();
    }

    private boolean isRefreshing() {
        return progressBar.getVisibility() == View.VISIBLE;
    }

    private void startRefresh(String text) {
        progressBar.setVisibility(View.VISIBLE);
        if (text != null) {
            textInfo.setTextColor(ContextCompat.getColor(this, R.color.white));
            textInfo.setText(text);
        }
    }

    @OnClick(R.id.btnChooseDefaultLanguage)
    void onSelectDefaultLanguage() {
        String lang = LocaleHelper.getLocale(getProduct().getLang()).getLanguage();
        LocaleHelper.getLanguageData(lang, true);
        final int position = ((LanguageDataAdapter) comboLanguages.getAdapter()).getPosition(lang);
        if (position >= 0) {
            comboLanguages.setSelection(position, true);
        }
    }

    @OnClick(R.id.btnClose)
    void onExit() {
        finish();
    }

    @OnClick(R.id.btnChooseImage)
    void onChooseImage() {
        if (cannotEdit(REQUEST_CHOOSE_IMAGE_AFTER_LOGIN)) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, READ_EXTERNAL_STORAGE) != PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{READ_EXTERNAL_STORAGE}, MY_PERMISSIONS_REQUEST_STORAGE);
        } else {
            EasyImage.openGallery(this, -1, false);
        }
    }

    private boolean cannotEdit(int loginRequestCode) {
        if (isRefreshing()) {
            Toast.makeText(this, R.string.cant_modify_if_refreshing, Toast.LENGTH_SHORT).show();
            return true;
        }
        //if user not logged in, we force to log
        if (isUserNotLoggedIn()) {
            startActivityForResult(new Intent(FullScreenImage.this, LoginActivity.class), loginRequestCode);
            return true;
        }
        return false;
    }

    @OnClick(R.id.btnAddImage)
    void onAddImage() {
        if (cannotEdit(REQUEST_ADD_IMAGE_AFTER_LOGIN)) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, CAMERA) != PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{CAMERA}, MY_PERMISSIONS_REQUEST_CAMERA);
        } else {
            EasyImage.openCamera(this, 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MY_PERMISSIONS_REQUEST_CAMERA && BaseFragment.isAllGranted(grantResults)) {
            onAddImage();
        }
        if (requestCode == MY_PERMISSIONS_REQUEST_STORAGE && BaseFragment.isAllGranted(grantResults)) {
            onChooseImage();
        }
    }

    private void updateSelectDefaultLanguageAction() {
        boolean isDefault = getCurrentLanguage().equals(LocaleHelper.getLocale(getProduct().getLang()).getLanguage());
        btnChooseDefaultLanguage.setVisibility(isDefault ? View.INVISIBLE : View.VISIBLE);
    }

    @OnClick(R.id.btnEditImage)
    void onStartEditExistingImage() {
        if (cannotEdit(REQUEST_EDIT_IMAGE_AFTER_LOGIN)) {
            return;
        }
        Product product = getProduct();
        final ProductImageField productImageField = getSelectedType();
        String language = getCurrentLanguage();
        //the rotation/crop set on the server
        final ImageTransformation transformation = ImageTransformation.getScreenTransformation(product, productImageField, language);
        if (transformation.isNotEmpty()) {
            new FileDownloader(getBaseContext()).download(transformation.getInitImageUrl(), file -> {
                //to delete the file after:
                lastViewedImage = file;
                cropRotateExisitingImageOnServer(file, getString(ImageKeyHelper.getResourceIdForEditAction(productImageField)), transformation);
            });
        }
    }

    private Product getProduct() {
        return (Product) getIntent().getSerializableExtra(ImageKeyHelper.PRODUCT);
    }

    @SuppressWarnings("unused")
    @OnItemSelected(R.id.comboLanguages)
    void onLanguageChanged() {
        LocaleHelper.LanguageData data = (LocaleHelper.LanguageData) comboLanguages.getSelectedItem();
        Product product = getProduct();
        if (!data.getCode().equals(getCurrentLanguage())) {
            getIntent().putExtra(ImageKeyHelper.LANGUAGE, data.getCode());
            getIntent().putExtra(ImageKeyHelper.IMAGE_URL, getImageUrlToDisplay(product));
            updateToolbarTitle(product);
            onRefresh(false);
        }
        updateSelectDefaultLanguageAction();
    }

    private ProductImageField getSelectedType() {
        return (ProductImageField) getIntent().getSerializableExtra(ImageKeyHelper.IMAGE_TYPE);
    }

    @SuppressWarnings("unused")
    @OnItemSelected(R.id.comboImageType)
    void onImageTypeChanged() {
        ProductImageField newTypeSelected = TYPE_IMAGE.get(comboImageType.getSelectedItemPosition());
        final ProductImageField selectedType = getSelectedType();
        if (newTypeSelected.equals(selectedType)) {
            return;
        }
        getIntent().putExtra(ImageKeyHelper.IMAGE_TYPE, newTypeSelected);
        getIntent().putExtra(ImageKeyHelper.IMAGE_URL, getImageUrlToDisplay(getProduct()));
        onRefresh(false);
        loadLanguage();
        updateToolbarTitle(getProduct());
    }

    private void cropRotateExisitingImageOnServer(File image, String title, ImageTransformation transformation) {
        Uri uri = Uri.fromFile(image);
        final CropImage.ActivityBuilder activityBuilder = CropImage.activity(uri)
            .setCropMenuCropButtonIcon(R.drawable.ic_check_white_24dp)
            .setAllowFlipping(false)
            //we just want crop size/rotation
            .setNoOutputImage(true)
            .setAllowRotation(true)
            .setAllowCounterRotation(true)
            .setAutoZoomEnabled(false)
            .setInitialRotation(transformation.getRotationInDegree())

            .setActivityTitle(title);
        if (transformation.getCropRectangle() != null) {
            activityBuilder.setInitialCropWindowRectangle(transformation.getCropRectangle());
        } else {
            activityBuilder.setInitialCropWindowPaddingRatio(0);
        }
        startActivityForResult(activityBuilder.getIntent(this, CropImageActivity.class), REQUEST_EDIT_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // do nothing
        if (requestCode == REQUEST_EDIT_IMAGE_AFTER_LOGIN) {
            if (resultCode == RESULT_OK) {
                onStartEditExistingImage();
            }
        } else if (requestCode == REQUEST_ADD_IMAGE_AFTER_LOGIN) {
            if (resultCode == RESULT_OK) {
                onAddImage();
            }
        } else if (requestCode == REQUEST_CHOOSE_IMAGE_AFTER_LOGIN) {
            if (resultCode == RESULT_OK) {
                onChooseImage();
            }
        } else if (requestCode == REQUEST_EDIT_IMAGE) {
            applyEditExistingImage(resultCode, data);
        } else {
            new PhotoReceiverHandler(this).onActivityResult(this, requestCode, resultCode, data);
        }
    }

    /**
     * @param resultCode should
     * @param dataFromCropActivity from the crop activity. If not, action is ignored
     */
    private void applyEditExistingImage(int resultCode, @Nullable Intent dataFromCropActivity) {
        //delete downoaded local file
        deleteLocalFiles();
        // if the selected language is not the same than current image we can't modify: only add
        if (isUserNotLoggedIn() || !updateLanguageStatus() || dataFromCropActivity == null) {
            return;
        }
        String imageUrl = getCurrentImageUrl();
        if (resultCode == Activity.RESULT_OK) {
            startRefresh(StringUtils.EMPTY);
            CropImage.ActivityResult result = CropImage.getActivityResult(dataFromCropActivity);
            final Product product = getProduct();
            ImageTransformation currentServerTransformation = ImageTransformation.getInitialServerTransformation(product, getSelectedType(), getCurrentLanguage());
            ImageTransformation newServerTransformation = ImageTransformation
                .toServerTransformation(new ImageTransformation(result.getRotation(), result.getCropRect()), product, getSelectedType(), getCurrentLanguage());
            boolean isModified = !currentServerTransformation.equals(newServerTransformation);
            if (isModified) {
                startRefresh(getString(R.string.toastSending));
                HashMap<String, String> imgMap = new HashMap<>();
                imgMap.put(ImageKeyHelper.IMG_ID, newServerTransformation.getInitImageId());
                ImageTransformation.addTransformToMap(newServerTransformation, imgMap);
                imgMap.put(ImageKeyHelper.PRODUCT_BARCODE, product.getCode());
                imgMap.put(ImageKeyHelper.IMAGE_STRING_ID, ImageKeyHelper.getImageStringKey(getSelectedType(), getCurrentLanguage()));
                mPhotoView.setVisibility(View.INVISIBLE);
                client.editImage(product.getCode(), imgMap, (value, response) -> {
                    if (value) {
                        setResult(RESULTCODE_MODIFIED);
                    }
                    reloadProduct();
                });
            } else {
                stopRefresh();
            }
        }
    }

    private void deleteLocalFiles() {
        if (lastViewedImage != null) {
            boolean deleted = lastViewedImage.delete();
            if (!deleted) {
                Log.w(FullScreenImage.class.getSimpleName(), "cant delete file " + lastViewedImage);
            } else {
                lastViewedImage = null;
            }
        }
    }

    /*For scheduling a postponed transition after the proper measures of the view are done
        and the view has been properly laid out in the View hierarchy*/
    private void scheduleStartPostponedTransition(final View sharedElement) {
        sharedElement.getViewTreeObserver().addOnPreDrawListener(
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    sharedElement.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        startPostponedEnterTransition();
                    }
                    return true;
                }
            });
    }

    /**
     * @param newPhotoFile photo selected by the user to be sent to the server.
     */
    @Override
    public void onPhotoReturned(File newPhotoFile) {
        startRefresh(getString(R.string.uploading_image));
        new Thread(() -> {
            ProductImage image = new ProductImage(getProduct().getCode(), getSelectedType(), newPhotoFile, getCurrentLanguage());
            image.setFilePath(newPhotoFile.getAbsolutePath());

            client.postImg(FullScreenImage.this, image, true, new ImageUploadListener() {
                @Override
                public void onSuccess() {
                    reloadProduct();
                    setResult(RESULTCODE_MODIFIED);
                }

                @Override
                public void onFailure(String message) {
                    Toast.makeText(FullScreenImage.this, message, Toast.LENGTH_LONG).show();
                    stopRefresh();
                }
            });
        }).start();
    }
}
