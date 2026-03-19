package com.hd.hdmobilepos.activity.payment.andso_payment.instant_refund;

import static android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
import static com.hd.hdmobilepos.network.transvc.PosTran.cTrTranHeader;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import androidx.camera.view.PreviewView;

import com.google.mlkit.vision.text.TextRecognizer;
import com.hd.hdmobilepos.GlobalApp;
import com.hd.hdmobilepos.R;
import com.hd.hdmobilepos.activity.BaseActivity;
import com.hd.hdmobilepos.activity.scan.PassportScanContract;
import com.hd.hdmobilepos.common.HDEditor;
import com.hd.hdmobilepos.common.NumberKeyboard;
import com.hd.hdmobilepos.common.TitleBar;
import com.hd.hdmobilepos.consts.PosCommonData;
import com.hd.hdmobilepos.database.HDMST.SYS_COMM_CD_DTL;
import com.hd.hdmobilepos.database.HdMstManager;
import com.hd.hdmobilepos.databinding.ActivityInstantRefundBinding;
import com.hd.hdmobilepos.network.ErrorCode;
import com.hd.hdmobilepos.network.NetworkListener;
import com.hd.hdmobilepos.network.SocketManagerEx;
import com.hd.hdmobilepos.network.model.GTFGB.TaxRefundVanReq;
import com.hd.hdmobilepos.network.model.GTFGB.TaxRefundVanRes;
import com.hd.hdmobilepos.network.model.HPOINT.HpntPassportHostRes;
import com.hd.hdmobilepos.network.model.HPOINT.HpntPassportHostResDir;
import com.hd.hdmobilepos.network.transvc.PosTran;
import com.hd.hdmobilepos.pos_data.PosStatus;
import com.hd.hdmobilepos.pos_data.SaleComplete;
import com.hd.hdmobilepos.pos_data.TaxRefund;
import com.hd.hdmobilepos.service.PosSalseService;
import com.hd.hdmobilepos.util.*;
import com.iookill.util.T;
import com.regula.documentreader.api.DocumentReader;
import com.regula.documentreader.api.completions.IDocumentReaderCompletion;
import com.regula.documentreader.api.completions.IDocumentReaderInitCompletion;
import com.regula.documentreader.api.config.ScannerConfig;
import com.regula.documentreader.api.enums.DocReaderAction;
import com.regula.documentreader.api.enums.Scenario;
import com.regula.documentreader.api.enums.eVisualFieldType;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Locale;

/**
 * 기타결제 - 즉시환급
 * @since 2019-04-29
 */

public class HDTaxRefundActivity extends BaseActivity implements View.OnClickListener {
    ActivityInstantRefundBinding binding;
    TaxRefundVanReq mTaxRefundVanReq;
    HpntPassportHostRes passportInfos;
    HpntPassportHostResDir passportInfosDir;
    boolean mIsTogleMan = true;
    String strGender = "M";
    String isSensed = "0";
    boolean checkLicense = false;
    String errMsg = "";
    private PreviewView previewView;
    private TextRecognizer recognizer;

    /********************************************************************************************************
     * Intent Factory
     *********************************************************************************************************/
    /**
     * 즉시환급 결제 호출 Intent 반환
     *
     * @param context Context
     * @return
     */
    public static Intent createIntent(Context context) {
        Intent intent = new Intent(context, HDTaxRefundActivity.class);
        return intent;
    }

    /**
     * 즉시환급 View Binding, 화면 display
     *
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // databinding 연결
        binding = setMainView(R.layout.activity_instant_refund);
        initData();
        initLayout();
        //initOCR();
    }

//    private void initOCR() {
//        showLoading(true, "여권인식 기능 활성화 중");
//        try {
//            InputStream licInput = getResources().openRawResource(R.raw.regula);
//            int available = licInput.available();
//            byte[] license = new byte[available];
//            licInput.read(license);
//
//            DocReaderConfig config = new DocReaderConfig(license);
//            config.setLicenseUpdate(false);
//
//            DocumentReader.Instance().initializeReader(HDTaxRefundActivity.this, config, new IDocumentReaderInitCompletion() {
//                @Override
//                public void onInitCompleted(boolean result, DocumentReaderException error) {
//                    if (result) {
//                        showLoading(false);
//                        T.i("인증 성공 결과 : " + error);
//                        checkLicense = true;
//                    } else {
//                        showLoading(false);
//                        T.i("인증 실패 에러 : " + error);
//
//                        String errorMsg = error.getMessage();
//                        if (errorMsg.contains("invalid date"))
//                            errMsg = "라이센스가 만료되어\n여권인식 기능 사용이\n불가합니다.\n재무팀에 연락바랍니다.";
//                        else
//                            errMsg = "여권인식 기능 사용이 불가합니다.\n여권 정보를 직접 입력해주세요.";
//                        showPopup(errMsg);
//                    }
//                }
//            });
//            licInput.close();
//        } catch (IOException e) {
//            T.e(e);
//            showPopup(e.getMessage());
//        }
//
//        DocumentReader.Instance().showScanner(HDTaxRefundActivity.this, completion);
//    }


    /********************************************************************************************************
     * UI
     *********************************************************************************************************/

    /**
     * view display(타이틀, 하단버튼, 가상키패드 등)
     */
    private void initLayout() {

        if (PosStatus.POST_TAX_REFUND_YN) {
            setTitleBar(TitleBar.TITLE_BTN_BACK, ResourceUtil.getString(R.string.title_post_refund), null);
            setBottomBar(ResourceUtil.getString(R.string.cancel),
                    ResourceUtil.getString(R.string.bottom_reg_passport),// 사후환급OCR
                    ResourceUtil.getString(R.string.bottom_post_refund));
        } else {
            setTitleBar(TitleBar.TITLE_BTN_BACK, ResourceUtil.getString(R.string.title_instant_refund), null);
            setBottomBar(ResourceUtil.getString(R.string.cancel),
                    ResourceUtil.getString(R.string.bottom_reg_passport),// 즉시환급OCR
                    ResourceUtil.getString(R.string.bottom_instant_refund));
        }


        setEnableBottomBar(1, false);
        setKeyboard();
        binding.etCountry.requestFocus();

        //binding.etCountry.setOnEditorActionListener(editorActionListener);

        binding.btnMan.setOnClickListener(this);
        binding.btnWoman.setOnClickListener(this);

        setData();

        binding.btnMan.setText("남");
        binding.btnWoman.setText("여");

        binding.etCountry.setInputType(TYPE_TEXT_FLAG_CAP_CHARACTERS);
        binding.etCountry.setEnableRemakeText(false);
        binding.etPassport.setInputType(TYPE_TEXT_FLAG_CAP_CHARACTERS);
        binding.etPassport.setEnableRemakeText(false);
        binding.etLastNm.setInputType(TYPE_TEXT_FLAG_CAP_CHARACTERS);
        binding.etLastNm.setEnableRemakeText(false);
        binding.etFirstNm.setInputType(TYPE_TEXT_FLAG_CAP_CHARACTERS);

        binding.etCountry.setText("");
        binding.etPassport.setText("");
        binding.etCountry.setText("");
        binding.etLastNm.setText("");
        binding.etFirstNm.setText("");
        binding.etBirth.setText("");
        binding.etValiditty.setText("");
    }

    /**
     * 가상키패드 셋팅
     */
    private void setKeyboard() {
        setNumberKeyboard(binding.etBirth);
        setNumberKeyboard(binding.etValiditty);
        setNumberKeyboard(binding.etBuisenessNo);
        setNumberKeyboard(binding.totPrice);

    }

    /**
     * 남/여 토글키
     */
    private void applyPassportGender(String sex) {
        if (sex == null || sex.trim().isEmpty()) {
            showPopup("성별을 확인해주세요.");
            return;
        }

        String normalizedSex = sex.trim().toUpperCase(Locale.ROOT);
        switch (normalizedSex) {
            case "M":
                mIsTogleMan = true;
                strGender = "M";
                changeToggle();
                return;
            case "F":
                mIsTogleMan = false;
                strGender = "F";
                changeToggle();
                return;
            default:
                Log.w("RESULT", "지원하지 않는 성별 코드: " + normalizedSex);
                showPopup("성별을 확인해주세요.");
        }
    }

    private void clearScannedPassportFields() {
        binding.etCountry.setText("");
        binding.etPassport.setText("");
        binding.etCountry.setText("");
        binding.etLastNm.setText("");
        binding.etFirstNm.setText("");
        binding.etBirth.setText("");
        binding.etValiditty.setText("");
    }

    private void applyPassportScanResult(PassportScanContract.ScanResult.Passport passport) {
        isSensed = "1";

        Log.d("RESULT", "여권 스캔 완료: nationality=" + passport.getNationality() +
                ", sex=" + passport.getSex() +
                ", passportNumberLength=" + passport.getPassportNumber().length());

        binding.etCountry.setText(passport.getNationality());
        binding.etPassport.setText(passport.getPassportNumber());
        binding.etLastNm.setText(passport.getLastName());
        binding.etFirstNm.setText(passport.getFirstName());
        binding.etBirth.setText(passport.getDateOfBirth());
        binding.etValiditty.setText(passport.getExpirationDate());
        applyPassportGender(passport.getSex());
    }

    private void applyScanResult(PassportScanContract.ScanResult scanResult) {
        if (scanResult instanceof PassportScanContract.ScanResult.Barcode) {
            PassportScanContract.ScanResult.Barcode barcode = (PassportScanContract.ScanResult.Barcode) scanResult;
            isSensed = "2";
            getHPointCustomInfo(barcode.getValue());
            return;
        }

        applyPassportScanResult((PassportScanContract.ScanResult.Passport) scanResult);
    }

    private void changeToggle() {
        if (mIsTogleMan) {
            binding.btnTogle.setBackgroundResource(R.drawable.gl_togle_2);
            binding.btnMan.setTextColor(getResources().getColor(R.color.white));
            binding.btnWoman.setTextColor(getResources().getColor(R.color.dark));

        } else {
            binding.btnTogle.setBackgroundResource(R.drawable.gl_togle_1);
            binding.btnMan.setTextColor(getResources().getColor(R.color.dark));
            binding.btnWoman.setTextColor(getResources().getColor(R.color.white));
        }
    }

    /********************************************************************************************************
     * Data
     *********************************************************************************************************/

    public void initData() {
        //mTaxRefundVanReq = new TaxRefundVanReq();
    }

    /**
     * 기본정보 셋팅
     */
    private void setData() {
        Dictionary<String, String> m_dicInputData = null;
        m_dicInputData = new PosSalseService().CheckTaxRefund();
        binding.etBuisenessNo.setText(m_dicInputData.get("VenCd"));
        binding.totPrice.setText(m_dicInputData.get("TotSaleAmt"));

        if (!binding.totPrice.getText().equals("0")) {
            setEnableBottomBar(1, true);
        }
    }

    /********************************************************************************************************
     * Event
     *********************************************************************************************************/

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PassportScanContract.REQUEST_CODE || resultCode != RESULT_OK) {
            return;
        }

        PassportScanContract.ScanResult scanResult = PassportScanContract.parseResult(data);
        if (scanResult == null) {
            Log.w("RESULT", "스캔 결과를 파싱하지 못했습니다.");
            return;
        }

        applyScanResult(scanResult);
    }

    /**
     * numberKeyPad action
     *
     * @param editText HDEditor
     * @param action   키패드 이벤트코드
     * @return
     */
    @Override
    public boolean onAction(HDEditor editText, int action) {
        if (action == NumberKeyboard.ACTION_ENTER) {
            switch (editText.getId()) {
                case R.id.etBirth:
                    if (checkValue(editText)) {
                        binding.etValiditty.requestFocus();
                    }
                    break;

                case R.id.etValiditty:
                    if (checkValue(editText)) {
                        binding.etBuisenessNo.requestFocus();
                    }
                    break;

                case R.id.etBuisenessNo:
                    if (checkValue(null)) {
                        mTaxRefundVanReq = new TaxRefundVanReq();
                        hideNumberKeyboard();
                        approvalProc();
                    }
                    break;
            }
        }
        return super.onAction(editText, action);
    }

    /**
     * 버튼 클릭
     *
     * @param v View
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btnMan:
                mIsTogleMan = true;
                strGender = "M";
                changeToggle();
                break;

            case R.id.btnWoman:
                mIsTogleMan = false;
                strGender = "F";
                changeToggle();
                break;
        }

    }

    /**
     * 하단 버튼 이벤트
     *
     * @param position int 버튼 index
     * @param name     String 버튼 명
     */
    public void onClickBottom(int position, String name) {

        switch (position) {
            // 취소
            case 0:
                finish();
                break;

            // 여권인식
            case 1:
                clearScannedPassportFields();
                Intent intent = PassportScanContract.createIntent(this);
                startActivityForResult(intent, PassportScanContract.REQUEST_CODE);

//                if(checkLicense)
//                    showScanner();
//                else

                    //showPopup(errMsg);
                break;

            // 즉시+사후환급
            case 2:
                mTaxRefundVanReq = new TaxRefundVanReq();
                //사후환급
                if (PosStatus.POST_TAX_REFUND_YN) {
                    showConfirm("사후 환급 처리를\n 하시겠습니까?", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == DialogInterface.BUTTON_POSITIVE) {
                                approvalPostProc();
                            }
                        }
                    });
                }
                //즉시환급
                else if (checkValue(null)) {
                    approvalProc();
                }
                break;
        }
    }


    /********************************************************************************************************
     *
     *********************************************************************************************************/
    /**
     * 즉시환급전문 승인정보 처리
     * 전문 데이터 처리 및 승인 요청 호출
     *
     * @return
     */
    public String approvalProc() {
        String strReturn = PosCommonData.RETURN_VALUE.NG;
        String strErrorMessage = "";

        try {
            Dictionary<String, String> m_dicInputData = null;
            m_dicInputData = new PosSalseService().CheckTaxRefund();
            // ** 전문생성용 Dic **
            TaxRefundVanReq taxRefundVanReq = new TaxRefundVanReq();
            Dictionary<String, String> dicInputData = new Hashtable<>();

            dicInputData.put("State", PosCommonData.PAYMENT_TYPE.USE);

            dicInputData.put("ApprVanId", m_dicInputData.get("ApprVanId"));
            dicInputData.put("SlipNo", "");                                                     // 원거래 일련번호
            dicInputData.put("VenCd", m_dicInputData.get("VenCd"));
            dicInputData.put("ApprTID", m_dicInputData.get("ApprTID"));
            dicInputData.put("TotItemQty", m_dicInputData.get("TotItemQty"));
            dicInputData.put("TotSaleAmt", m_dicInputData.get("TotSaleAmt"));
            dicInputData.put("Vat", m_dicInputData.get("Vat"));
            dicInputData.put("ProdName", m_dicInputData.get("ProdName"));
            dicInputData.put("RfndTp", m_dicInputData.get("RfndTp"));
            dicInputData.put("BrandCd", m_dicInputData.get("BrandCd"));
            dicInputData.put("BrandNm", m_dicInputData.get("BrandNm"));
            dicInputData.put("Stamp", m_dicInputData.get("Stamp"));

            dicInputData.put("EngNm", binding.etLastNm.getText() + " " + binding.etFirstNm.getText());
            dicInputData.put("PassportNo", binding.etPassport.getText());
            dicInputData.put("Country", binding.etCountry.getText());
            dicInputData.put("Sex", strGender);
            dicInputData.put("BirthDay", binding.etBirth.getText());
            dicInputData.put("ValidDt", binding.etValiditty.getText());

            dicInputData.put("InputTp", "2");

            if(PosStatus.DIRECT_HPOINT.equals("1")){
                if (null != passportInfosDir)
                    dicInputData.put("HPCardNo", passportInfosDir.CUST_CARD_NO);
            }else{
                if (null != passportInfos)
                    dicInputData.put("HPCardNo", passportInfos.CUST_CARD_NO);
            }

            dicInputData.put("isSensed", isSensed);


            //** 즉시환급 처리 **

            TaxRefund cTaxRefund = new TaxRefund();
            strReturn = cTaxRefund.ApprTaxRefund(dicInputData);

            if (!strReturn.equals(PosCommonData.RETURN_VALUE.OK)) {
                showPopup(strReturn);
            }
            if (PosStatus.TRAIN_MODE_YN == PosCommonData.EN_TRAIN_MODE_YN.Train)    // 연습모드
            {
                TaxRefundVanRes cTaxRefundRsp = new TaxRefundVanRes();

                cTaxRefundRsp.taxRefundHeader.CM_LEN = "0512";
                cTaxRefundRsp.taxRefundHeader.CM_INQ = "*";
                cTaxRefundRsp.taxRefundHeader.CM_MSG_LEN = "0508";
                cTaxRefundRsp.taxRefundHeader.CM_PROC_ID = "03AB";
                cTaxRefundRsp.taxRefundHeader.CM_SEQ = PosStatus.TRAN_NO;
                cTaxRefundRsp.taxRefundHeader.CM_INQ_TYPE = "CR";
                cTaxRefundRsp.taxRefundTotalHeader.HD_MSG_LEN = "0512";
                cTaxRefundRsp.taxRefundTotalHeader.HD_CONN_ID = "POS ";
                cTaxRefundRsp.taxRefundTotalHeader.HD_STR_CD = StringUtil.padRight(PosStatus.STORE_CD, 3);
                cTaxRefundRsp.taxRefundTotalHeader.HD_POS_NO = PosStatus.POS_NO;
                cTaxRefundRsp.taxRefundTotalHeader.HD_DEAL_NO = StringUtil.padRight(PosStatus.TRAN_NO, 4);
                cTaxRefundRsp.taxRefundTotalHeader.HD_DT = DateUtil.getCurrentDate(DateUtil.FORMAT_MMDD);
                cTaxRefundRsp.taxRefundTotalHeader.HD_TM = DateUtil.getCurrentDate(DateUtil.FORMAT_HHMM);
                cTaxRefundRsp.RFND_CO_TP = dicInputData.get("ApprVanId");

                if (dicInputData.get("State").equals(PosCommonData.PAYMENT_TYPE.USE))
                    cTaxRefundRsp.RFND_TP = "101";
                else if (dicInputData.get("State").equals(PosCommonData.PAYMENT_TYPE.SEARCH))
                    cTaxRefundRsp.RFND_TP = "201";
                else
                    cTaxRefundRsp.RFND_TP = "301";

                cTaxRefundRsp.DEAL_NO = PosStatus.STORE_CD + PosStatus.POS_NO + PosStatus.SALE_DATE + PosStatus.TRAN_NO;
                cTaxRefundRsp.SLIP_NO = dicInputData.get("SlipNo");
                cTaxRefundRsp.BIZ_NO = dicInputData.get("VenCd");
                cTaxRefundRsp.TRMNL_ID = dicInputData.get("ApprTID");
                cTaxRefundRsp.GSALE_CNT = CmUtil.ToInt(dicInputData.get("TotItemQty"));
                cTaxRefundRsp.GSALE_AMT = CmUtil.ToInt(dicInputData.get("TotSaleAmt"));
                cTaxRefundRsp.VAT = CmUtil.ToInt(dicInputData.get("Vat"));
                cTaxRefundRsp.ENG_NM = dicInputData.get("EngNm");
                cTaxRefundRsp.PASSPT_NO = dicInputData.get("PassportNo");
                cTaxRefundRsp.PASSPT_NATION_CD = dicInputData.get("Country");
                cTaxRefundRsp.PASSPT_GNDR = dicInputData.get("Sex");
                cTaxRefundRsp.PASSPT_BIRTH = dicInputData.get("BirthDay");
                cTaxRefundRsp.PASSPT_EXPIRE = dicInputData.get("ValidDt");
                cTaxRefundRsp.RES_CD = "0000";
                cTaxRefundRsp.RES_MSG = "";
                cTaxRefundRsp.SHOP_NM = "";
                cTaxRefundRsp.PDT_CNT = "0001";
                cTaxRefundRsp.TAKEOUT_EXPIRE = "";
                cTaxRefundRsp.IMDT_RFND_YN = PosStatus.POST_TAX_REFUND_YN ? "A" : "Y";
                cTaxRefundRsp.PAY_AMT = 1000;
                cTaxRefundRsp.RFND_BLNC_AMT = 2900;
                cTaxRefundRsp.PERSNL_TAX_TP = "1";
                cTaxRefundRsp.PDT_CD = "";
                cTaxRefundRsp.PDT_CONT = dicInputData.get("ProdName");
                cTaxRefundRsp.RFND_DTM = DateUtil.getCurrentDate();

                //cTaxRefundRsp.getTrainRes(null);
                returnRefundSuccess(dicInputData, cTaxRefundRsp);
            } else {
                inqTaxRefund(dicInputData);
            }
        } catch (Exception ex) {
            T.e(ex.getMessage());

            strReturn = PosCommonData.RETURN_VALUE.NG;
        }
        return strReturn;
    }

    /********************************************************************************************************
     *
     *********************************************************************************************************/
    /**
     * 사후환급전문 승인정보 처리
     * 전문 데이터 처리 및 승인 요청 호출
     *
     * @return
     */
    public String approvalPostProc() {
        String strReturn = PosCommonData.RETURN_VALUE.NG;
        String strErrorMessage = "";

        try {
            Dictionary<String, String> m_dicInputData = null;
            m_dicInputData = new PosSalseService().CheckTaxRefund();
            // ** 전문생성용 Dic **
            //TaxRefundVanReq taxRefundVanReq = new TaxRefundVanReq();
            Dictionary<String, String> dicInputData = new Hashtable<>();

            dicInputData.put("State", PosCommonData.PAYMENT_TYPE.USE);

            dicInputData.put("ApprVanId", m_dicInputData.get("ApprVanId"));
            dicInputData.put("SlipNo", "");                                                     // 원거래 일련번호
            dicInputData.put("VenCd", m_dicInputData.get("VenCd"));
            dicInputData.put("ApprTID", m_dicInputData.get("ApprTID"));
            dicInputData.put("TotItemQty", m_dicInputData.get("TotItemQty"));
            dicInputData.put("TotSaleAmt", m_dicInputData.get("TotSaleAmt"));
            dicInputData.put("Vat", m_dicInputData.get("Vat"));
            dicInputData.put("ProdName", m_dicInputData.get("ProdName"));
            dicInputData.put("RfndTp", m_dicInputData.get("RfndTp"));
            dicInputData.put("BrandCd", m_dicInputData.get("BrandCd"));
            dicInputData.put("BrandNm", m_dicInputData.get("BrandNm"));
            dicInputData.put("Stamp", m_dicInputData.get("Stamp"));

            dicInputData.put("EngNm", binding.etLastNm.getText() + " " + binding.etFirstNm.getText());
            dicInputData.put("PassportNo", binding.etPassport.getText());
            dicInputData.put("Country", binding.etCountry.getText());
            dicInputData.put("Sex", strGender);
            dicInputData.put("BirthDay", binding.etBirth.getText());
            dicInputData.put("ValidDt", binding.etValiditty.getText());

            dicInputData.put("InputTp", "2");

            if(PosStatus.DIRECT_HPOINT.equals("1")){
                if (null != passportInfosDir)
                    dicInputData.put("HPCardNo", passportInfosDir.CUST_CARD_NO);
            }else{
                if (null != passportInfos)
                    dicInputData.put("HPCardNo", passportInfos.CUST_CARD_NO);
            }

            dicInputData.put("isSensed", isSensed);

            //** 사후환급 처리 **

            TaxRefund cTaxRefund = new TaxRefund();
            strReturn = cTaxRefund.ApprTaxRefund(dicInputData);

            if (!strReturn.equals(PosCommonData.RETURN_VALUE.OK)) {
                showPopup(strReturn);
            }
            if (PosStatus.TRAIN_MODE_YN == PosCommonData.EN_TRAIN_MODE_YN.Train)    // 연습모드
            {
                TaxRefundVanRes cTaxRefundRsp = new TaxRefundVanRes();

                cTaxRefundRsp.taxRefundHeader.CM_LEN = "0512";
                cTaxRefundRsp.taxRefundHeader.CM_INQ = "*";
                cTaxRefundRsp.taxRefundHeader.CM_MSG_LEN = "0508";
                cTaxRefundRsp.taxRefundHeader.CM_PROC_ID = "03AB";
                cTaxRefundRsp.taxRefundHeader.CM_SEQ = PosStatus.TRAN_NO;
                cTaxRefundRsp.taxRefundHeader.CM_INQ_TYPE = "CR";
                cTaxRefundRsp.taxRefundTotalHeader.HD_MSG_LEN = "0512";
                cTaxRefundRsp.taxRefundTotalHeader.HD_CONN_ID = "POS ";
                cTaxRefundRsp.taxRefundTotalHeader.HD_STR_CD = StringUtil.padRight(PosStatus.STORE_CD, 3);
                cTaxRefundRsp.taxRefundTotalHeader.HD_POS_NO = PosStatus.POS_NO;
                cTaxRefundRsp.taxRefundTotalHeader.HD_DEAL_NO = StringUtil.padRight(PosStatus.TRAN_NO, 4);
                cTaxRefundRsp.taxRefundTotalHeader.HD_DT = DateUtil.getCurrentDate(DateUtil.FORMAT_MMDD);
                cTaxRefundRsp.taxRefundTotalHeader.HD_TM = DateUtil.getCurrentDate(DateUtil.FORMAT_HHMM);
                cTaxRefundRsp.RFND_CO_TP = dicInputData.get("ApprVanId");

                if (dicInputData.get("State").equals(PosCommonData.PAYMENT_TYPE.USE))
                    cTaxRefundRsp.RFND_TP = "101";
                else if (dicInputData.get("State").equals(PosCommonData.PAYMENT_TYPE.SEARCH))
                    cTaxRefundRsp.RFND_TP = "201";
                else
                    cTaxRefundRsp.RFND_TP = "301";

                //cTaxRefundRsp.DEAL_NO = PosStatus.STORE_CD + PosStatus.POS_NO + PosStatus.SALE_DATE + PosStatus.TRAN_NO;
                cTaxRefundRsp.DEAL_NO = makeDealNo();
                cTaxRefundRsp.SLIP_NO = dicInputData.get("SlipNo");
                cTaxRefundRsp.BIZ_NO = dicInputData.get("VenCd");
                cTaxRefundRsp.TRMNL_ID = dicInputData.get("ApprTID");
                cTaxRefundRsp.GSALE_CNT = CmUtil.ToInt(dicInputData.get("TotItemQty"));
                cTaxRefundRsp.GSALE_AMT = CmUtil.ToInt(dicInputData.get("TotSaleAmt"));
                cTaxRefundRsp.VAT = CmUtil.ToInt(dicInputData.get("Vat"));
                cTaxRefundRsp.ENG_NM = dicInputData.get("EngNm");
                cTaxRefundRsp.PASSPT_NO = dicInputData.get("PassportNo");
                cTaxRefundRsp.PASSPT_NATION_CD = dicInputData.get("Country");
                cTaxRefundRsp.PASSPT_GNDR = dicInputData.get("Sex");
                cTaxRefundRsp.PASSPT_BIRTH = dicInputData.get("BirthDay");
                cTaxRefundRsp.PASSPT_EXPIRE = dicInputData.get("ValidDt");
                cTaxRefundRsp.RES_CD = "0000";
                cTaxRefundRsp.RES_MSG = "";
                cTaxRefundRsp.SHOP_NM = "";
                cTaxRefundRsp.PDT_CNT = "0001";
                cTaxRefundRsp.TAKEOUT_EXPIRE = "";
                cTaxRefundRsp.IMDT_RFND_YN = PosStatus.POST_TAX_REFUND_YN ? "A" : "Y";
                cTaxRefundRsp.PAY_AMT = 1000;
                cTaxRefundRsp.RFND_BLNC_AMT = 2900;
                cTaxRefundRsp.PERSNL_TAX_TP = "1";
                cTaxRefundRsp.PDT_CD = "";
                cTaxRefundRsp.PDT_CONT = dicInputData.get("ProdName");
                cTaxRefundRsp.RFND_DTM = DateUtil.getCurrentDate();
                cTaxRefundRsp.FILLER = dicInputData.get("BrandNm");

                //cTaxRefundRsp.getTrainRes(null);
                returnRefundSuccess(dicInputData, cTaxRefundRsp);
            } else {
                inqTaxRefund(dicInputData);
            }
        } catch (Exception ex) {
            T.e(ex.getMessage());

            strReturn = PosCommonData.RETURN_VALUE.NG;
        }
        return strReturn;
    }

    private String makeDealNo() {
        String Cd_Val1 = String.valueOf(String.valueOf(cTrTranHeader.NSaleAmt).length() - 1);

        String TmpAmt = StringUtil.padLeft(String.valueOf(cTrTranHeader.NSaleAmt), 10);
        int SumVal2 = 0;

        for (int i = 0; i < TmpAmt.length(); i++) {
            if (i % 2 == 0)
                SumVal2 += Integer.parseInt(TmpAmt.substring(i, i + 1));
            else
                SumVal2 += Integer.parseInt(TmpAmt.substring(i, i + 1)) * 3;
        }

        String Cd_Val2 = String.valueOf(SumVal2 % 10);

        String TmpInfo = StringUtil.MidH(cTrTranHeader.SaleDt, 2, 6) + PosTran.cTrTranHeader.PosNo + cTrTranHeader.DealNo;


        int SumVal3 = 0;

        for (int i = 0; i < TmpInfo.length(); i++) {
            if (i % 2 == 0)
                SumVal3 += Integer.parseInt(TmpInfo.substring(i, i + 1));
            else
                SumVal3 += Integer.parseInt(TmpInfo.substring(i, i + 1)) * 3;
        }
        String Cd_Val3 = String.valueOf(SumVal3 % 10);

        String dealNo = StringUtil.MidH(cTrTranHeader.SaleDt, 2, 6) + cTrTranHeader.PosNo + cTrTranHeader.DealNo + "99" + PosStatus.STORE_CD + Cd_Val1 + Cd_Val2 + Cd_Val3;

        return dealNo;
    }

    /**
     * 입력 정보 확인
     *
     * @param v
     * @return
     */
    private boolean checkValue(View v) {
        try {
            if (v == null || v.getId() == R.id.etCountry) {
                if (binding.etCountry.length() == 0) {
                    showPopup("국적을 입력해주세요.");
                    return false;
                }

                if (binding.etCountry.length() != 3) {
                    showPopup("국적을 확인해주세요.");
                    binding.etCountry.setText("");
                    return false;
                }

                String country = binding.etCountry.getText();
                SYS_COMM_CD_DTL cDtl = HdMstManager.getInstance().getCountryDlt("5002", country);

                if (cDtl == null) {
                    showPopup("해당 국적코드가 존재하지 않습니다.");
                    return false;
                }
            }
            if (v == null || v.getId() == R.id.etPassport) {
                if (binding.etPassport.length() == 0) {
                    showPopup("여권 번호를 입력해주세요.");
                    return false;
                }
            }
            if (v == null || v.getId() == R.id.etLastNm) {
                if (binding.etLastNm.length() == 0) {
                    showPopup("성명(성)을 입력해주세요.");
                    return false;
                }
            }
            if (v == null || v.getId() == R.id.etFirstNm) {
                if (binding.etFirstNm.length() == 0) {
                    showPopup("성명(이름)을 입력해주세요.");
                    return false;
                }
            }
            if (v == null || v.getId() == R.id.etBirth) {
                if (binding.etBirth.length() == 0) {
                    showPopup("생년월일을 입력해주세요.");
                    binding.etBirth.setText("");
                    return false;
                }

                if (!DateUtil.checkDateValidate(binding.etBirth.getText())) {
                    showPopup("생년월일을 확인하세요.");
                    binding.etBirth.setText("");
                    return false;
                }
            }
            if (v == null || v.getId() == R.id.etValiditty) {
                if (binding.etValiditty.length() == 0) {
                    showPopup("유효기간을 입력해주세요.");
                    binding.etValiditty.setText("");
                    return false;
                }

                if (!DateUtil.checkDateValidate(binding.etValiditty.getText())) {
                    showPopup("유효기간을 확인하세요.");
                    binding.etValiditty.setText("");
                    return false;
                }
            }
            return true;
        } catch (Exception ex) {
            T.e(ex.getMessage());
            return false;
        }

//        return strReturn;
    }

    /********************************************************************************************************
     * Network(TR)
     ********************************************************************************************************
     * @param dicInputData*/

    int nCnt = 0;

    /**
     * 즉시/사후환급 승인 요청
     *
     * @param dicInputData
     */
    public void inqTaxRefund(Dictionary<String, String> dicInputData) {
        if (PosStatus.POST_TAX_REFUND_YN) {
            T.i("사후환급 승인");
            showLoading(true, "사후환급 승인 처리중입니다.");
        } else {
            T.i("즉시환급 승인");
            showLoading(true, "즉시환급 승인 처리중입니다.");

        }
        taxRefundDataMake(dicInputData);
        //HDString sSendData = mTaxRefundVanReq.getSendData();

        SocketManagerEx socketManagerEx = new SocketManagerEx();
        socketManagerEx.inqTaxRefund(mTaxRefundVanReq, true, new NetworkListener<TaxRefundVanRes>() {
            @Override
            public void onResponse(TaxRefundVanRes res) {
                if (res.isSuccess()) {
                    nCnt = 0;
                    if (!res.RES_CD.equals("0000")) {
                        showPopup(res.RES_MSG);
                        return;
                    }
                    returnRefundSuccess(dicInputData, res);
                } else {
                    showLoading(false);
                    showPopup("환급 승인 실패했습니다.\n" + "(" + res.RES_MSG + ")");
//                    if (nCnt < 3) {
//                        showRetry("승인 실패했습니다. 재시도 하시겠습니까?\n" + "(" + res.RES_MSG + ")", "재시도", "취소", new DialogInterface.OnClickListener() {
//                            @Override
//                            public void onClick(DialogInterface dialog, int which) {
//                                if (which == DialogInterface.BUTTON_POSITIVE) {
//                                    nCnt++;
//                                    inqTaxRefund(dicInputData);
//                                } else {
//                                    nCnt = 0;
//                                }
//                            }
//                        });
//                    } else {
//                        nCnt = 0;
//                        showPopup("즉시환급 승인 실패했습니다.\n" + "(" + res.RES_MSG + ")");
//                    }
                }
            }
        });
    }

    /**
     * 즉시+사후환급 승인처리
     *
     * @param dicInputData Dictionary<String, String> 전문데이터
     * @param res          TaxRefundVanRes            승인응답 데이터
     */
    private void returnRefundSuccess(Dictionary<String, String> dicInputData, TaxRefundVanRes res) {
        new TaxRefund().SetTaxRefundData(dicInputData, res);
        SaleComplete cSaleComplete = new SaleComplete();
        cSaleComplete.WriteTranLogFile();
        showLoading(false);

        if (PosStatus.POST_TAX_REFUND_YN) {
            showPopup("사후환급승인완료", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    //startActivityForResult(HDPaymentActivity.createIntent(getActivity()), PAYMENT_REQ);
                    SaleComplete.saleCompleteCheck(false);
                    if (callCompleteStep()) {
                        return;
                    } else {
                        setResult(RESULT_OK);
                        // 데이터가 저장 문제가 안생기게 200mili 적용
                        Handler handler = new Handler();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                finish();
                            }
                        }, 200);
                    }
                }
            });
        } else {
            showPopup(StringUtil.NumToPay(res.PAY_AMT) + "원 환급 되었습니다.", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    SaleComplete.saleCompleteCheck(false);
                    if (callCompleteStep()) {
                        return;
                    } else {
                        setResult(RESULT_OK);
                        // 데이터가 저장 문제가 안생기게 200mili 적용
                        Handler handler = new Handler();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                finish();
                            }
                        }, 200);
                    }
                }
            });

        }
    }

    /**
     * TaxRefund 전문 생성
     *
     * @param dicInputData 전문 데이터
     */
    private void taxRefundDataMake(Dictionary<String, String> dicInputData) {
        // BODY
        mTaxRefundVanReq.RFND_CO_TP = dicInputData.get("ApprVanId");                                 //밴구분, 리펀드사 구분 1:GB, 2:GTF 3.NICE

        if (dicInputData.get("State").equals(PosCommonData.PAYMENT_TYPE.USE))
            mTaxRefundVanReq.RFND_TP = "101";
        else if (dicInputData.get("State").equals(PosCommonData.PAYMENT_TYPE.SEARCH))
            mTaxRefundVanReq.RFND_TP = "201";
        else
            mTaxRefundVanReq.RFND_TP = "301";

        mTaxRefundVanReq.DEAL_NO = PosStatus.STORE_CD + PosStatus.POS_NO + PosStatus.SALE_DATE + PosStatus.TRAN_NO;

        if (dicInputData.get("State").equals(PosCommonData.PAYMENT_TYPE.USE))
            mTaxRefundVanReq.SLIP_NO = "";
        else
            mTaxRefundVanReq.SLIP_NO = dicInputData.get("SlipNo");

        if (dicInputData.get("State").equals(PosCommonData.PAYMENT_TYPE.SEARCH))
            mTaxRefundVanReq.BIZ_NO = "";
        else
            mTaxRefundVanReq.BIZ_NO = dicInputData.get("VenCd");

        if (dicInputData.get("State").equals(PosCommonData.PAYMENT_TYPE.USE)) {
            mTaxRefundVanReq.TRMNL_ID = dicInputData.get("ApprTID");
            mTaxRefundVanReq.GSALE_CNT = dicInputData.get("TotItemQty");
            mTaxRefundVanReq.GSALE_AMT = dicInputData.get("TotSaleAmt");
            mTaxRefundVanReq.VAT = dicInputData.get("Vat");
            mTaxRefundVanReq.ENG_NM = dicInputData.get("EngNm");

            mTaxRefundVanReq.PASSPT_NO = dicInputData.get("PassportNo");
            mTaxRefundVanReq.PASSPT_NATION_CD = dicInputData.get("Country");
            mTaxRefundVanReq.PASSPT_GNDR = dicInputData.get("Sex");
            mTaxRefundVanReq.PASSPT_BIRTH = dicInputData.get("BirthDay");
            mTaxRefundVanReq.PASSPT_EXPIRE = dicInputData.get("ValidDt");

            mTaxRefundVanReq.RES_CD = "";
            mTaxRefundVanReq.RES_MSG = "";
            mTaxRefundVanReq.SHOP_NM = "";
            mTaxRefundVanReq.PDT_CNT = "0001";
            mTaxRefundVanReq.TAKEOUT_EXPIRE = "";
            mTaxRefundVanReq.IMDT_RFND_YN = PosStatus.POST_TAX_REFUND_YN ? "A" : "Y";
            mTaxRefundVanReq.PAY_AMT = dicInputData.get("TotSaleAmt");
            mTaxRefundVanReq.RFND_BLNC_AMT = "0000000000";
            mTaxRefundVanReq.PERSNL_TAX_TP = "1";
            mTaxRefundVanReq.PDT_CD = "";
            mTaxRefundVanReq.PDT_CONT = dicInputData.get("ProdName");
            mTaxRefundVanReq.RFND_DTM = DateUtil.getCurrentFullDate3();
            mTaxRefundVanReq.FILLER = PosStatus.POST_TAX_REFUND_YN ? dicInputData.get("BrandNm") : "";
        } else {
            mTaxRefundVanReq.TRMNL_ID = "";
            mTaxRefundVanReq.GSALE_CNT = "0000";
            mTaxRefundVanReq.GSALE_AMT = "000000000000";
            mTaxRefundVanReq.VAT = "00000000";
            mTaxRefundVanReq.ENG_NM = "";
            mTaxRefundVanReq.PASSPT_NO = "";
            mTaxRefundVanReq.PASSPT_NATION_CD = "";
            mTaxRefundVanReq.PASSPT_GNDR = "";
            mTaxRefundVanReq.PASSPT_BIRTH = "";
            mTaxRefundVanReq.PASSPT_EXPIRE = "";
            mTaxRefundVanReq.RES_CD = "";
            mTaxRefundVanReq.RES_MSG = "";
            mTaxRefundVanReq.SHOP_NM = "";
            mTaxRefundVanReq.PDT_CNT = "0000";
            mTaxRefundVanReq.TAKEOUT_EXPIRE = "";
            mTaxRefundVanReq.IMDT_RFND_YN = "";
            mTaxRefundVanReq.PAY_AMT = "000000000000";
            mTaxRefundVanReq.RFND_BLNC_AMT = "0000000000";
            mTaxRefundVanReq.PERSNL_TAX_TP = "";
            mTaxRefundVanReq.PDT_CD = "";
            mTaxRefundVanReq.PDT_CONT = "";
            mTaxRefundVanReq.RFND_DTM = DateUtil.getCurrentFullDate3();
            mTaxRefundVanReq.FILLER = "000000";
        }
    }

    // 2021.02.18 chung : 속도느려짐 개선 머지.
    @Override
    public void finish() {
        //즉시,사후 환급시 스캐너 꺼짐 현상 수정 - 20250528 함세강선임 START
        GlobalApp.openScanner();
        //즉시,사후 환급시 스캐너 꺼짐 현상 수정 - 20250528 함세강선임 END
        finish(ANI_SLIDE_DOWN);
        binding = null;
    }

    // 즉시환급+사후환급 OCR - START
    protected final IDocumentReaderInitCompletion initCompletion = (result, error) -> {
        T.i("인증 결과 : " + result);
        T.i("인증 에러 : " + error);
        showLoading(false);

        DocumentReader.Instance().customization().edit().setShowHelpAnimation(false).apply();
        DocumentReader.Instance().functionality().edit().setVideoCaptureMotionControl(true).apply();
        DocumentReader.Instance().processParams().timeout = 30.0;
        DocumentReader.Instance().processParams().timeoutFromFirstDocType = 3.0;
        DocumentReader.Instance().customization().edit().setShowResultStatusMessages(true).apply();
        DocumentReader.Instance().functionality().edit().setShowCloseButton(true).apply();

    };

    protected final IDocumentReaderCompletion completion = (action, results, error) -> {
        T.i("호출 결과 : " + action);
        T.i("최초 결과 : " + results);
        T.i("호출 에러 : " + error);

        String SurGiven = null;
        String SurName = null;
        String GivenName = null;
        String passportNo = null;
        String NationalityCode = null;
        String genderCode = null;
        String dateOfBirth = null;
        String dateOfExpiry = null;

        if (action == DocReaderAction.COMPLETE) {
            if (results != null && results.chipPage != 0) {
                String accessKey = null;
                if ((accessKey = results.getTextFieldValueByType(eVisualFieldType.FT_MRZ_STRINGS)) != null && !accessKey.isEmpty()) {
                    accessKey = results.getTextFieldValueByType(eVisualFieldType.FT_MRZ_STRINGS).replace("^", "").replace("\n", "");
                }
                isSensed = "1";

                SurGiven = results.getTextFieldValueByType(eVisualFieldType.FT_SURNAME_AND_GIVEN_NAMES);
                SurName = results.getTextFieldValueByType(eVisualFieldType.FT_SURNAME);
                GivenName = results.getTextFieldValueByType(eVisualFieldType.FT_GIVEN_NAMES);
                passportNo = results.getTextFieldValueByType(eVisualFieldType.FT_DOCUMENT_NUMBER);
                NationalityCode = results.getTextFieldValueByType(eVisualFieldType.FT_NATIONALITY_CODE);
                genderCode = results.getTextFieldValueByType(eVisualFieldType.FT_SEX);
                dateOfBirth = results.getTextFieldValueByType(eVisualFieldType.FT_DATE_OF_BIRTH);
                dateOfExpiry = results.getTextFieldValueByType(eVisualFieldType.FT_DATE_OF_EXPIRY);

                try {
                    AESUtil aes256Util = new AESUtil("0001000100010001");
                    T.i(aes256Util.encrypt("MRZ인식 결과 : " + accessKey));
                    T.i(aes256Util.encrypt("SURGIVEN : " + SurGiven));
                    T.i(aes256Util.encrypt("Passport : " + passportNo));
                    T.i(aes256Util.encrypt("SUR NAME : " + SurName));
                    T.i(aes256Util.encrypt("GIVEN NAME : " + GivenName));
                    T.i(aes256Util.encrypt("GENDER CODE : " + genderCode));
                    T.i(aes256Util.encrypt("BIRTH : " + dateOfBirth));
                    T.i(aes256Util.encrypt("EXPIRY : " + dateOfExpiry));
                } catch (Exception e) {
                    T.e(accessKey);
                }

                SimpleDateFormat input = new SimpleDateFormat("yy. MM. dd");
                SimpleDateFormat output = new SimpleDateFormat("yyMMdd");

                try {
                    Date birthDate = input.parse(dateOfBirth);
                    Date expiryDate = input.parse(dateOfExpiry);
                    dateOfBirth = output.format(birthDate);
                    dateOfExpiry = output.format(expiryDate);
                } catch (ParseException e) {
                    e.printStackTrace();
                }

                binding.etCountry.setText(NationalityCode);
                binding.etPassport.setText(passportNo);

                if (SurName == null && GivenName == null) {
                    binding.etLastNm.setText(SurGiven);
                    binding.etFirstNm.setText(" ");
                } else {
                    binding.etLastNm.setText(SurName);
                    binding.etFirstNm.setText(GivenName);
                }

                if (genderCode.trim().equals("M")) {
                    mIsTogleMan = true;
                    strGender = "M";
                    changeToggle();
                } else {
                    mIsTogleMan = false;
                    strGender = "F";
                    changeToggle();
                }

                binding.etBirth.setText(dateOfBirth);
                binding.etValiditty.setText(dateOfExpiry);

                return;

            }

            else if (results != null && results.chipPage == 0) {
                String accessKey = null;
                if ((accessKey = results.getTextFieldValueByType(eVisualFieldType.FT_OTHER)) != null && !accessKey.isEmpty()) {
                    accessKey = results.getTextFieldValueByType(eVisualFieldType.FT_OTHER);
                }
                T.i("바코드 인식 결과 : " + accessKey);

                if(accessKey.length() == 16){
                    isSensed = "2";
                    getHPointCustomInfo(accessKey);
                }
                else{
                    showPopup("H.Point 바코드 또는 여권하단 MRZ코드를\n"+ "센싱해주세요.");
                }

                return;
            }
        }
        else if(action == DocReaderAction.TIMEOUT){
            showPopup("시간 초과 : 여권인식을 재시도해주세요.");
        }
        else {
            return;
        }
    };

    public void showScanner() {
        DocumentReader.Instance().functionality().edit().setShowCloseButton(true).apply();
        DocumentReader.Instance().functionality().edit().setVideoCaptureMotionControl(true).apply();
        DocumentReader.Instance().customization().edit().setShowHelpAnimation(false).apply();
        DocumentReader.Instance().customization().edit().setShowStatusMessages(false).apply();
        DocumentReader.Instance().customization().edit().setResultStatus("H.Point 바코드 또는 여권하단 MRZ코드를\n"+"센싱해주세요.").apply();
        DocumentReader.Instance().customization().edit().setResultStatusBackgroundColor("#32a852").apply();
        DocumentReader.Instance().customization().edit().setResultStatusTextSize(18).apply();
        DocumentReader.Instance().customization().edit().setShowResultStatusMessages(true).apply();
        DocumentReader.Instance().processParams().timeout = 30.0;
        DocumentReader.Instance().processParams().timeoutFromFirstDocType = 3.0;

        ScannerConfig scannerConfig = new ScannerConfig.Builder(Scenario.SCENARIO_MRZ_OR_BARCODE).build();
        DocumentReader.Instance().showScanner(HDTaxRefundActivity.this, scannerConfig, completion);
    }
    // 즉시환급OCR - END

    /**
     * HPoint 고객 정보 조회
     *
     * @param barcodeNo 다음 조회할 고객번호
     */
    public void getHPointCustomInfo(String barcodeNo) {


        showLoading(true);
        if(PosStatus.DIRECT_HPOINT.equals("1")){
            socketManagerEx.getHPointPassportInfoDir(barcodeNo, new NetworkListener<HpntPassportHostResDir>() {
                @Override
                public void onResponse(HpntPassportHostResDir res) {
                    showLoading(false);
                    if (res.isSuccess()) {
                        passportInfosDir = res;
                        T.w(passportInfosDir);

                        // 연계되어있지 않은 경우
                        if (res.CUST_NO == null || res.CUST_NO.isEmpty()) {
                            showPopup("H.Point 미연계상태입니다.");
                        }

                        //여권 값 세팅 + 자동 적립 가능하게끔 세팅

                        else{
                            String SurName = res.CUST_SUR==null || res.CUST_SUR.isEmpty()? null :passportInfosDir.CUST_SUR;
                            String GivenName = res.CUST_GIVEN==null || res.CUST_GIVEN.isEmpty()? null :passportInfosDir.CUST_GIVEN;
                            String passportNo = res.CUST_PASSPORT_NO==null || res.CUST_PASSPORT_NO.isEmpty()? null :passportInfosDir.CUST_PASSPORT_NO;
                            String NationalityCode = res.CUST_COUNTRY==null || res.CUST_COUNTRY.isEmpty()? null :passportInfosDir.CUST_COUNTRY;
                            String genderCode = res.CUST_SEX==null || res.CUST_SEX.isEmpty()? null :passportInfosDir.CUST_SEX;
                            String dateOfBirth = res.PASSPORT_BIRTH_DT==null || res.PASSPORT_BIRTH_DT.isEmpty()? null : passportInfosDir.PASSPORT_BIRTH_DT.substring(2);
                            String dateOfExpiry = res.PASSPORT_ISSUE_DT==null || res.PASSPORT_ISSUE_DT.isEmpty() ? null :passportInfosDir.PASSPORT_ISSUE_DT.substring(2);

                            T.i("Passport : " + passportNo);
                            T.i("SUR NAME : " + SurName);
                            T.i("GIVEN NAME : " + GivenName);
                            T.i("GENDER CODE : " + genderCode);
                            T.i("BIRTH : " + dateOfBirth);
                            T.i("EXPIRY : " + dateOfExpiry);

                            SimpleDateFormat input = new SimpleDateFormat("yy. MM. dd");
                            SimpleDateFormat output = new SimpleDateFormat("yyMMdd");

                            try {
                                if(dateOfBirth != null){
                                    Date birthDate = input.parse(dateOfBirth);
                                    dateOfBirth = output.format(birthDate);
                                }
                                if(dateOfExpiry != null){
                                    Date expiryDate = input.parse(dateOfExpiry);
                                    dateOfExpiry = output.format(expiryDate);
                                }
                            } catch (ParseException e) {
                                e.printStackTrace();
                            }
                            if (null == SurName || null == GivenName || null == passportNo
                                    || null == NationalityCode|| null == dateOfBirth
                                    || null == dateOfExpiry)
                                showPopup("H.Point 앱에서 여권정보를 확인해주세요.");

                            binding.etCountry.setText(NationalityCode);
                            binding.etPassport.setText(passportNo);

                            binding.etLastNm.setText(SurName);
                            binding.etFirstNm.setText(GivenName);


                            if(genderCode != null){
                                if (genderCode.trim().equals("M")) {
                                    mIsTogleMan = true;
                                    strGender = "M";
                                } else {
                                    mIsTogleMan = false;
                                    strGender = "F";
                                }
                                changeToggle();
                            }
                            else{
                                showPopup("성별을 확인해주세요.");
                            }

                            binding.etBirth.setText(dateOfBirth);
                            binding.etValiditty.setText(dateOfExpiry);

                            return;
                        }
                    } else if (res.getErrorCodeEx() == ErrorCode.E_NET_RECEIVE_FAIL) {
                        showPopup(res.errorMsg);
                    } else if (res.getErrorCodeEx() == ErrorCode.E_HOST_TIME) {
                        showPopup(res.errorMsg);
                    } else {
                        passportInfosDir = null;
                        showPopup(res.MSG1);
                        return;
                    }
                }
            });
        }else{
            socketManagerEx.getHPointPassportInfo(barcodeNo, new NetworkListener<HpntPassportHostRes>() {
                @Override
                public void onResponse(HpntPassportHostRes res) {
                    showLoading(false);
                    if (res.isSuccess()) {
                        passportInfos = res;
                        T.w(passportInfos);

                        // 연계되어있지 않은 경우
                        if (res.CUST_NO == null || res.CUST_NO.isEmpty()) {
                            showPopup("H.Point 미연계상태입니다.");
                        }

                        //여권 값 세팅 + 자동 적립 가능하게끔 세팅

                        else{
                            String SurName = res.CUST_SUR==null || res.CUST_SUR.isEmpty()? null :passportInfos.CUST_SUR;
                            String GivenName = res.CUST_GIVEN==null || res.CUST_GIVEN.isEmpty()? null :passportInfos.CUST_GIVEN;
                            String passportNo = res.CUST_PASSPORT_NO==null || res.CUST_PASSPORT_NO.isEmpty()? null :passportInfos.CUST_PASSPORT_NO;
                            String NationalityCode = res.CUST_COUNTRY==null || res.CUST_COUNTRY.isEmpty()? null :passportInfos.CUST_COUNTRY;
                            String genderCode = res.CUST_SEX==null || res.CUST_SEX.isEmpty()? null :passportInfos.CUST_SEX;
                            String dateOfBirth = res.PASSPORT_BIRTH_DT==null || res.PASSPORT_BIRTH_DT.isEmpty()? null : passportInfos.PASSPORT_BIRTH_DT.substring(2);
                            String dateOfExpiry = res.PASSPORT_ISSUE_DT==null || res.PASSPORT_ISSUE_DT.isEmpty() ? null :passportInfos.PASSPORT_ISSUE_DT.substring(2);

                            T.i("Passport : " + passportNo);
                            T.i("SUR NAME : " + SurName);
                            T.i("GIVEN NAME : " + GivenName);
                            T.i("GENDER CODE : " + genderCode);
                            T.i("BIRTH : " + dateOfBirth);
                            T.i("EXPIRY : " + dateOfExpiry);

                            SimpleDateFormat input = new SimpleDateFormat("yy. MM. dd");
                            SimpleDateFormat output = new SimpleDateFormat("yyMMdd");

                            try {
                                if(dateOfBirth != null){
                                    Date birthDate = input.parse(dateOfBirth);
                                    dateOfBirth = output.format(birthDate);
                                }
                                if(dateOfExpiry != null){
                                    Date expiryDate = input.parse(dateOfExpiry);
                                    dateOfExpiry = output.format(expiryDate);
                                }
                            } catch (ParseException e) {
                                e.printStackTrace();
                            }
                            if (null == SurName || null == GivenName || null == passportNo
                                    || null == NationalityCode|| null == dateOfBirth
                                    || null == dateOfExpiry)
                                showPopup("H.Point 앱에서 여권정보를 확인해주세요.");

                            binding.etCountry.setText(NationalityCode);
                            binding.etPassport.setText(passportNo);

                            binding.etLastNm.setText(SurName);
                            binding.etFirstNm.setText(GivenName);


                            if(genderCode != null){
                                if (genderCode.trim().equals("M")) {
                                    mIsTogleMan = true;
                                    strGender = "M";
                                } else {
                                    mIsTogleMan = false;
                                    strGender = "F";
                                }
                                changeToggle();
                            }
                            else{
                                showPopup("성별을 확인해주세요.");
                            }

                            binding.etBirth.setText(dateOfBirth);
                            binding.etValiditty.setText(dateOfExpiry);

                            return;
                        }
                    } else if (res.getErrorCodeEx() == ErrorCode.E_NET_RECEIVE_FAIL) {
                        showPopup(res.errorMsg);
                    } else if (res.getErrorCodeEx() == ErrorCode.E_HOST_TIME) {
                        showPopup(res.errorMsg);
                    } else {
                        passportInfos = null;
                        showPopup(res.MSG1);
                        return;
                    }
                }
            });
        }
    }
}
