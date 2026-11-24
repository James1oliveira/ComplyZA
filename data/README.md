# Source documents

Put the official English PDFs of the Acts here, for example:

| File | Act | Where to get it |
|---|---|---|
| `popia.pdf` | Protection of Personal Information Act 4 of 2013 | Information Regulator website or gov.za (Acts section) |
| `fica.pdf` | Financial Intelligence Centre Act 38 of 2001 | FIC website (fic.gov.za) |

Tips:
- Use an **English-only** version. Some Government Gazette PDFs alternate English and Afrikaans pages,
  which confuses the section parser.
- `data/popia.pdf` is used by `EvaluationIT`. Committing it lets CI run the evaluation.
  Official texts of legislation are not protected by copyright in South Africa
  (Copyright Act 98 of 1978, s12(8)(a)), but check the terms of the site you download from.
- This folder is excluded from the Docker image (`.dockerignore`). Documents are loaded into the
  database through `POST /admin/ingest`, not baked into the image.
