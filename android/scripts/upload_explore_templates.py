"""
上傳「官方精選範本」到 Firestore：explore_templates/current

範本由 export_explore_templates.mjs 用網頁自己的程式與景點資料產生（計畫 D5 方案 B），
App 開探索頁時先讀這份，讀不到才用 App 內建的 assets/explore_templates.json。

  身分（擇一）：
    ① scripts/serviceAccount.json（Firebase 管理員金鑰，已 gitignore）
    ② 沒有金鑰時改用 gcloud 登入身分——先執行一次 gcloud auth application-default login
  Firestore 規則需允許登入者讀取 explore_templates（firestore.rules 已加並部署，2026-09-24）

用法：
  node scripts/export_explore_templates.mjs          # 先重新產生
  python scripts/upload_explore_templates.py --dry-run
  python scripts/upload_explore_templates.py
"""

import argparse
import json
import os

import firebase_admin
from firebase_admin import credentials, firestore

HERE = os.path.dirname(__file__)
SERVICE_ACCOUNT = os.path.join(HERE, "serviceAccount.json")
SOURCE = os.path.join(HERE, "..", "app", "src", "main", "assets", "explore_templates.json")
PROJECT_ID = "project-720a680b-3ad1-40d1-b07"   # 同 .firebaserc


def get_credential():
    """有 serviceAccount.json 就用它；沒有就用 gcloud 登入身分（不必下載管理員金鑰）。

    gcloud 身分需先執行一次：gcloud auth application-default login
    用專案擁有者帳號登入即可，授權跟著帳號走，隨時可用 revoke 撤銷。
    """
    if os.path.exists(SERVICE_ACCOUNT):
        print("使用 serviceAccount.json")
        return credentials.Certificate(SERVICE_ACCOUNT)
    try:
        cred = credentials.ApplicationDefault()
        cred.get_credential()   # 提早確認有登入，錯誤訊息才看得懂
    except Exception as e:
        raise SystemExit(
            "❌ 找不到 serviceAccount.json，也沒有 gcloud 登入身分。\n"
            "   請先執行：gcloud auth application-default login\n"
            f"   （原始錯誤：{e}）"
        )
    print("使用 gcloud 登入身分（Application Default Credentials）")
    return cred


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="只檢查內容，不寫入")
    args = parser.parse_args()

    with open(SOURCE, encoding="utf-8") as f:
        data = json.load(f)
    size = len(json.dumps(data, ensure_ascii=False).encode("utf-8"))
    print(f"範本 {len(data['templates'])} 份，約 {size / 1024:.0f} KB（Firestore 單一文件上限 1 MB）")
    print(f"景點資料時間：{data.get('poiGeneratedAt')}，產生時間：{data.get('generatedAt')}")
    if size > 900 * 1024:
        raise SystemExit("❌ 太大，需要拆成多份文件")
    if args.dry_run:
        return

    firebase_admin.initialize_app(get_credential(), {"projectId": PROJECT_ID})
    firestore.client().collection("explore_templates").document("current").set(data)
    print("✅ 已寫入 explore_templates/current")


if __name__ == "__main__":
    main()
