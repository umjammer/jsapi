#pragma once
#include <iostream>

class Synthesizer
{
public:
	Synthesizer(const wchar_t* engineName);
    virtual ~Synthesizer();

	void Speak( LPCWSTR text );
    void SpeakSSML(LPCWSTR ssml);

    boolean Cancel(){
			if(  SUCCEEDED( cpVoice->Speak(NULL, SPF_PURGEBEFORESPEAK, NULL) )  ){
				return true;
			}
			else {
				return false;
			}			
		}

		boolean Pause(){
			if(  SUCCEEDED( cpVoice->Pause() )  ){
				return true;
			}
			else {
				return false;
			}	
		}

		boolean Resume(){
						if(  SUCCEEDED( cpVoice->Resume() )  ){
				return true;
			}
			else {
				return false;
			}	
		}

		HANDLE getEventHandler(){
			
			return cpVoice->GetNotifyEventHandle();
		}
    HRESULT	hr;

private:					

    CComPtr<ISpVoice> cpVoice;

};