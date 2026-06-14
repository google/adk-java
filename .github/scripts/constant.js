/*
Copyright 2026 Google LLC. All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
let CONSTANT_VALUES = {
  GLOBALS: {
    LABELS: {
      BUG: 'bug',
      DOCUMENTATION: 'documentation',
      GOOD_FIRST_ISSUE: 'good first issue',
      ENHANCEMENT: 'enhancement',
      QUESTION: 'question',
      JAVA: 'java',
      SAMPLE: 'sample',
      TESTING: 'testing',
      CONTRIB: 'contrib',
      DEPENDENCIES: 'dependencies',
      DUPLICATE: 'duplicate',
      GITHUB: 'github',
      NEEDS_UPDATE: 'needs update',
      READY_TO_PULL: 'ready to pull'
    },
    STATE: { CLOSED: 'closed' },
  },
  MODULE: {
    CSAT: {
      YES: 'Yes',
      NO: 'No',
      BASE_URL: 'https://docs.google.com/forms/d/e/1FAIpQLSeh96UhNq0-d4zdbKLj01Or56eqXom9iaw5sVquaB7Sno1SRg/viewform?usp=pp_url&',
      SATISFACTION_PARAM: 'entry.885968193=',
      ISSUEID_PARAM: '&entry.1737461264=',
      MSG: 'Are you satisfied with the resolution of your issue?',
    }
  }

};
module.exports = CONSTANT_VALUES;
